package tasks.bundle

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * What of one compiled class the checks of [BundleClasses] need: how it is declared, what it extends and implements,
 * and the access flags of its methods.
 */
internal class ClassFacts(
	val name: String,
	val access: Int,
	val superName: String?,
	val interfaces: List<String>,
	/** Access flags by name and descriptor, as in `getFoo()Ljava/lang/String;` */
	val methods: Map<String, Int>,
) {

	val isInterface: Boolean
		get() = access and Opcodes.ACC_INTERFACE != 0

	val isAbstract: Boolean
		get() = access and Opcodes.ACC_ABSTRACT != 0

	/** What a `default` method of an interface is on the JVM: it has a body, and is neither static nor private. */
	val defaultMethods: List<String>
		get() = if (!isInterface) {
			emptyList()
		} else {
			methods.filterValues {
				it and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0
			}.keys.filterNot { it.startsWith("<") }
		}

	fun hasBodyFor(method: String): Boolean = methods[method]?.let { it and Opcodes.ACC_ABSTRACT == 0 } == true
}

/**
 * Every class there is to the parsers, told apart by where an installed application has it from: the parsers
 * themselves (the bundle), what the application is made of (its contract and its libraries), and what Android
 * brings (the platform). Classes are read from their jars as they are asked for.
 */
internal class BundleClasses(
	bundleJar: File,
	contractJar: File,
	libraryJars: Collection<File>,
	androidJar: File,
) : Closeable {

	enum class Origin { BUNDLE, APP, PLATFORM }

	private val opened = ArrayList<ZipFile>()
	private val bundle = index(bundleJar)
	private val platform = index(androidJar)
	private val app = LinkedHashMap<String, ZipFile>()
	private val facts = HashMap<String, ClassFacts?>()

	/** Names of the classes of the contract alone. */
	val contractNames: Set<String>

	/** Names of every class of the parsers. */
	val bundleNames: Set<String>
		get() = bundle.keys

	init {
		val contract = index(contractJar)
		contractNames = contract.keys
		app.putAll(contract)
		for (jar in libraryJars) {
			for ((name, zip) in index(jar)) {
				app.putIfAbsent(name, zip)
			}
		}
	}

	private fun index(jar: File): Map<String, ZipFile> {
		val zip = ZipFile(jar)
		opened += zip
		val result = LinkedHashMap<String, ZipFile>()
		for (entry in zip.entries()) {
			if (!entry.isDirectory && entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/")) {
				result[entry.name.removeSuffix(".class")] = zip
			}
		}
		return result
	}

	override fun close() {
		opened.forEach { it.close() }
	}

	fun origin(name: String): Origin? = when {
		name in bundle -> Origin.BUNDLE
		name in app -> Origin.APP
		name in platform -> Origin.PLATFORM
		else -> null
	}

	fun bytesOfBundleClass(name: String): ByteArray {
		val zip = bundle.getValue(name)
		return zip.getInputStream(zip.getEntry("$name.class")).use { it.readBytes() }
	}

	fun facts(name: String): ClassFacts? = facts.getOrPut(name) {
		val zip = bundle[name] ?: app[name] ?: platform[name] ?: return@getOrPut null
		val bytes = zip.getInputStream(zip.getEntry("$name.class")).use { it.readBytes() }
		val methods = LinkedHashMap<String, Int>()
		var result: ClassFacts? = null
		ClassReader(bytes).accept(
			object : ClassVisitor(Opcodes.ASM9) {

				private var access = 0
				private var superName: String? = null
				private var interfaces: List<String> = emptyList()

				override fun visit(
					version: Int,
					access: Int,
					name: String,
					signature: String?,
					superName: String?,
					interfaces: Array<String>?,
				) {
					this.access = access
					this.superName = superName
					this.interfaces = interfaces?.toList().orEmpty()
					result = ClassFacts(name, access, superName, this.interfaces, methods)
				}

				override fun visitMethod(
					access: Int,
					name: String,
					descriptor: String,
					signature: String?,
					exceptions: Array<String>?,
				): MethodVisitor? {
					methods[name + descriptor] = access
					return null
				}
			},
			ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
		)
		result
	}

	/** An application-side interface and every application-side interface above it. */
	fun appInterfacesWithSupers(name: String, into: MutableSet<String> = LinkedHashSet()): MutableSet<String> {
		if (origin(name) != Origin.APP || !into.add(name)) {
			return into
		}
		facts(name)?.interfaces?.forEach { appInterfacesWithSupers(it, into) }
		return into
	}

	/**
	 * Parts of the parsers that hand an application's interface a default method they do not have: it is a method
	 * the application's own copy of that interface has no code for, see [checkNoBorrowedDefaults].
	 */
	fun findBorrowedDefaults(): List<String> {
		val problems = ArrayList<String>()
		for (name in bundleNames.sorted()) {
			val facts = facts(name) ?: continue
			scanCode(name, problems)
			if (facts.isInterface || facts.isAbstract) {
				continue
			}
			problems += missingDefaults(facts)
		}
		return problems
	}

	/** R1: a class that can be made, for the app-side interfaces it implements. */
	private fun missingDefaults(facts: ClassFacts): List<String> = borrowedDefaults(facts).map { (name, method) ->
		"${facts.name} does not declare $method, a default method of ${name.replace('/', '.')}"
	}

	/**
	 * The default methods of an application's interfaces that nothing of the parsers gives a body to, for a class of
	 * theirs or for an interface of theirs (which is what a lambda is made for): each as the interface and the method.
	 */
	private fun borrowedDefaults(start: ClassFacts): List<Pair<String, String>> {
		val appInterfaces = LinkedHashSet<String>()
		val bundleInterfaces = LinkedHashSet<String>()
		val bundleClasses = ArrayList<ClassFacts>()

		fun collect(name: String) {
			when (origin(name)) {
				Origin.BUNDLE -> if (bundleInterfaces.add(name)) {
					facts(name)?.interfaces?.forEach(::collect)
				}

				Origin.APP -> appInterfacesWithSupers(name, appInterfaces)
				else -> Unit
			}
		}

		if (start.isInterface) {
			collect(start.name)
		} else {
			var current: ClassFacts? = start
			while (current != null) {
				bundleClasses += current
				current.interfaces.forEach(::collect)
				// An application's class, or the platform's, takes care of the interfaces it has
				current = current.superName?.takeIf { origin(it) == Origin.BUNDLE }?.let(::facts)
			}
		}
		val borrowed = ArrayList<Pair<String, String>>()
		for (name in appInterfaces) {
			for (method in facts(name)?.defaultMethods.orEmpty()) {
				val declared = bundleClasses.any { it.hasBodyFor(method) } ||
					bundleInterfaces.any { facts(it)?.hasBodyFor(method) == true }
				if (!declared) {
					borrowed += name to method
				}
			}
		}
		return borrowed
	}

	/** R2, R3 and R4: what the code of a class does with the interfaces of the application. */
	private fun scanCode(name: String, problems: MutableList<String>) {
		ClassReader(bytesOfBundleClass(name)).accept(
			object : ClassVisitor(Opcodes.ASM9) {

				override fun visitMethod(
					access: Int,
					methodName: String,
					descriptor: String,
					signature: String?,
					exceptions: Array<String>?,
				): MethodVisitor {
					val where = "$name.$methodName"
					return object : MethodVisitor(Opcodes.ASM9) {

						override fun visitMethodInsn(
							opcode: Int,
							owner: String,
							calledName: String,
							calledDescriptor: String,
							isInterface: Boolean,
						) {
							if (!isInterface || origin(owner) != Origin.APP) {
								return
							}
							val called = "${owner.replace('/', '.')}.$calledName"
							if (opcode == Opcodes.INVOKESPECIAL) {
								problems += "$where calls $called with super, a default method of an application's interface"
							} else if (opcode == Opcodes.INVOKESTATIC) {
								problems += "$where calls $called, a static method of an application's interface"
							}
						}

						override fun visitInvokeDynamicInsn(
							dynamicName: String,
							dynamicDescriptor: String,
							bootstrapMethodHandle: Handle,
							vararg bootstrapMethodArguments: Any?,
						) {
							// A reference to a method (Foo::bar) is a call written another way
							for (argument in bootstrapMethodArguments) {
								if (argument !is Handle || !argument.isInterface || origin(argument.owner) != Origin.APP) {
									continue
								}
								val referred = "${argument.owner.replace('/', '.')}.${argument.name}"
								if (argument.tag == Opcodes.H_INVOKESTATIC) {
									problems += "$where refers to $referred, a static method of an application's interface"
								} else if (argument.tag == Opcodes.H_INVOKESPECIAL) {
									problems += "$where refers to $referred with super, a default method of an " +
										"application's interface"
								}
							}
							if (bootstrapMethodHandle.owner != "java/lang/invoke/LambdaMetafactory") {
								return
							}
							val target = Type.getReturnType(dynamicDescriptor).internalName
							when (origin(target)) {
								Origin.APP -> {
									val interfaces = appInterfacesWithSupers(target)
									if (interfaces.any { facts(it)?.defaultMethods?.isNotEmpty() == true }) {
										problems += "$where makes a lambda for ${target.replace('/', '.')}, an application's " +
											"interface with default methods"
									}
								}

								// An interface of the parsers built on one of the application's: the class made for
								// the lambda is in no jar to be looked at, so it is worked out from the interface
								Origin.BUNDLE -> facts(target)?.let(::borrowedDefaults)?.forEach { (name, method) ->
									problems += "$where makes a lambda for ${target.replace('/', '.')}, which leaves $method to " +
										"${name.replace('/', '.')}, an application's interface with default methods"
								}

								else -> Unit
							}
						}
					}
				}
			},
			ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
		)
	}
}
