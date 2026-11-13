# Parsers that reach the app without an app update

The app is built with the parsers inside it, and can also take newer ones from a file published from this
repository. It looks for one when it is opened, when a manga is opened, and as soon as a source fails. What it finds
is put in use while it runs: a source that failed is tried again with the newer parsers, and sources added since
the app was built appear in its catalog. The user is not asked or shown anything.

This is how a fix merged here gets to people in minutes instead of with the next release of the app.

## Publishing

Run **Publish parsers bundle** from the Actions tab, on `master`. There is nothing to fill in and nobody to
confirm: it goes straight through, in two steps.

1. **Checks what is on `master`.** Every source has to have its parser, and the parsers have to fit the apps
   they are meant for (see [The checks](#the-checks)). No site is visited.
2. **Signs and uploads** two files to the `parsers-bundle` release:

| File | What it is |
|---|---|
| `parsers-v1-<version>.zip` | The parsers, as Android runs them (dex). About 2 MB. |
| `parsers-v1.json` | The manifest: which file, its size and hashes, and a signature over all of that. |

Apps read the manifest, and download the zip only if it is newer than what they have.

Every installed app that sees a publication takes it, with no step in between and nobody watching. **Nothing is
tested against the sites when publishing.** That is done for every pull request, before it is merged: see
[PULL_REQUESTS.md](PULL_REQUESTS.md). What is pushed to `master` without a pull request is tested by whoever
pushes it, or not at all.

It publishes whatever is on `master`, also when no parser changed since the last publication: every app then
downloads the same parsers once more. Run it when there is something to send.

### When it does not go as planned

- **The run fails before its "Publish" step:** nothing is published. The log of the step that failed says why: a
  source without a parser, parsers using something the apps do not have, or a key that is missing or not the one
  the apps trust.
- **Only the last step fails**, the one that removes older files: the publication is out. Starting the run again
  is refused, as what is published is already as new, and is not needed.
- **An old run is started again:** it publishes nothing. Once something newer is out, an older build cannot be
  put over it.
- **The run was started on another branch:** it does nothing. Only `master` is published.

### Taking a publication back

There is no deleting one: an app that has taken parsers never goes back to older ones. Revert the commit on
`master` and publish again. The revert is a newer commit, so its parsers replace the bad ones.

An app that cannot load a publication at all, or that crashes twice inside its parsers, gives it up by itself and
returns to the parsers it was built with, until something newer is published.

## The key

The zip is code that runs inside the app with everything the app can reach. The app only takes parsers whose
manifest is signed with the one key it was built to trust: where the file was downloaded from counts for nothing.

**Whoever has the private key, or can make the workflow sign something, can run code on the phone of everyone
using the app.** It is the `PARSERS_SIGNING_KEY` secret of this repository. Keep the list of people who can
change workflows and secrets here as short as the list of people who can release the app.

In the workflow the key is in the environment of one step, the one that signs, in a job that compiles and packs the
parsers and runs none of their code. Keep it that way: a parser is code anyone may have sent in a pull request, and
its start-up code runs wherever a parser is made. A test or a task that makes parsers belongs in the job that has
no secrets. See [PULL_REQUESTS.md](PULL_REQUESTS.md).

Setting it up, once:

```
./gradlew generateParsersSigningKey -PkeyFile=/somewhere/outside/the/repo/parsers-signing-key.pem
```

- The file it writes is the private key. Paste its contents into the `PARSERS_SIGNING_KEY` secret (Settings >
  Secrets and variables > Actions), keep a copy somewhere safe and offline, and delete the file.
- The line it prints is the public key. It goes in two places: `parsersPublicKey` in `gradle.properties` here, so
  that a publication signed with any other key fails instead of going out, and `parsersBundlePublicKey` in the
  app's `app/build.gradle`.

If the private key is lost, no app in use can be given parsers again until it is updated to a release with a new
key. If it leaks, the same, and urgently.

A fork of the app has to use its own key and its own place to publish, or take no parsers at all: see the comment
next to `PARSERS_BUNDLE_URL` in the app's `app/build.gradle`.

## What can and cannot be published

The library is in two parts:

- `src/main` is the parsers: every site, their base classes and their helpers. All of it is in the zip and is
  replaced as a whole.
- `src/contract` is what the app and the parsers have in common: the models, `MangaParser`,
  `MangaLoaderContext`, the exceptions. It stays inside the app and is **not** in the zip. An installed app has
  it as it was on the day the app was built.

So anything under `src/main` can be changed and published. A change under `src/contract` reaches nobody until the
app itself is updated, and parsers that need it would crash in every app that does not have it yet. That is what
`ParsersApi.VERSION` is for:

- Each version of the contract is written down in `api/parsers-api-v<N>.txt`: its classes and members, and the
  versions of the libraries (Kotlin, OkHttp, jsoup...) apps of that version were built with.
- `./gradlew checkParsersApi` fails if `src/contract` no longer matches the record of its version.
- An app only takes parsers made for its own version of the contract.

When the contract really has to change: raise `ParsersApi.VERSION`, run `./gradlew dumpParsersApi`, commit the new
record. From then on what is published is for apps built with the new contract. Apps not yet updated keep the
last parsers published for theirs and get nothing newer until they are updated, so do it rarely, and release the
app soon after.

Updating a library does not need a new version by itself: the parsers are still checked against the versions in
the record, and the check says so if they come to use something newer.

### The checks

`./gradlew checkParsersBundle` goes over everything the parsers refer to outside of themselves and fails on what
an app would not have:

- something of the JDK or of `org.json` that Android lacks (`JSONObject.optFloat` is one: use
  `getFloatOrDefault`),
- something of a library that is newer than the version in the record,
- something of the contract that is not in the record,
- a member of the contract whose signature has `java.time`, `java.util.function` or `java.util.stream` in it: apps
  for Android 7 and older hold those under other names.

It runs on every pull request and before every publication. Such a reference used to be a crash on the phone
of whoever opened that source.

It also looks at what the parsers are made of, and fails on:

- **A class outside of `org.koitharu.kotatsu.parsers`**, or one named like a class of `src/contract`. The app takes
  nothing else from the download, and uses its own older, shrunk copy of any other class. Keep helpers under
  `org.koitharu.kotatsu.parsers`.
- **A default or static method of an app interface that the parsers lean on.** The app is built for Android 6 and
  up, so its shrinker turns a `default` method of an interface into an abstract one (the code goes to a helper
  class) and moves a `static` one there too. Classes made with the app get matching methods, downloaded classes do
  not. So a parser class must not:
  - implement an interface of the app (or of a library) without declaring every `default` method of it itself;
  - write a lambda, or pass a Kotlin lambda, where such an interface is expected (`element.traverse { node, depth -> }`
    on jsoup's `NodeVisitor`: write an object that declares both `head` and `tail`);
  - call `super` into a default method of an app interface, or call a static method declared on one. The
    Kotlin compiler writes such a call itself for a property with a default getter in an interface of the contract,
    so a property of the contract is abstract, and each base class implements it.

  Interfaces of Android itself are fine, and so is a Kotlin `DefaultImpls`. A fix that is wanted in the contract
  needs a new `ParsersApi.VERSION`.
- **A part of Android newer than the minimum**, 26 (the Android of the smallest phone that loads the parsers). The
  dex is looked at rather than the classes, since the tool that makes it writes in older stand-ins for some newer
  methods. The versions come from the SDK's `data/api-versions.xml`, found next to the `android.jar` used, and a
  member counts as new when it, or the class it was found in, came later than that. The check lists them all in
  `build/parsersBundle/platform-level.txt`.

## Locally

```
./gradlew checkParsersBundle
./gradlew parsersBundle -PparsersSigningKeyFile=<a test key> -PparsersPublicKey=<its public half> -PparsersVersion=<seconds since the epoch>
```

The files are written to `build/parsersBundle/out`. Making a bundle needs the Android SDK (`ANDROID_HOME`, or
`sdk.dir` in `local.properties`) for its `android.jar`. Building the library itself does not.

To try a bundle in the app, build the app with `parsers_bundle_url` and `parsers_bundle_key` in its
`local.properties` set to a folder served over HTTP and to the public half of the test key.

## Limits

- Android 8 and later. On older phones the app keeps the parsers it was built with, as before.
- The version of a set of parsers is the time of its commit. An app takes a publication only if that is later than
  both the parsers it was built with and any it has already taken.
- The downloaded parsers are kept in the app's code cache. Updating the app or clearing its cache removes them,
  and they are downloaded again if they are still newer than the ones in the app.
- The app's setting "Update sources automatically" turns all of this off.
