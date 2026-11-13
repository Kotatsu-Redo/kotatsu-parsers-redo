# Pull requests: tested by themselves, merged when the tests pass

Every pull request to `master` is tested without anyone asking. Once a maintainer has read it and said it may be
merged, GitHub merges it by itself the moment its checks are green.

What is merged here is signed and sent to every installed app by **Publish parsers bundle**, which starts by
itself when a merge changes the parsers (see [BUNDLE.md](BUNDLE.md)). That is why the one thing that is never
automatic is somebody reading the change.
This is also the only place parsers are tested against their sites: publishing does not test them again.

## What happens to a pull request

1. **Its checks run** (`pull-request.yml`), with nothing to do for anyone:
   - **Parser tests**: the sources it changes are run against their sites, with `MangaParserTest` (see
     [Which sources are tested](#which-sources-are-tested)). The results are on the page of the run.
     Green only when everything tested passed. A failing source, one Cloudflare kept the tests away from, or a
     change no test covers makes it red: that is for a person to look at.
   - **Parsers bundle**: the parsers must still fit the apps already installed.
2. **A maintainer reads the diff** and presses *Enable auto-merge* on the pull request. That can be done at once,
   without waiting for the checks.
3. **GitHub merges it when both checks are green.** If they already are, the button merges right away. If a check
   is red, nothing is merged; a maintainer can still merge by hand after looking at why (a site that is down or
   behind Cloudflare, for instance).

For a pull request of your own, step 2 is one click when you open it.

## Which sources are tested

- A source whose own file the pull request changes is always tested.
- For each changed file that several sources share, 10 of the sources that build on it are tested: a base class
  such as `MadaraParser` (its subclasses), or a helper in a site folder (the sources of that folder, and those
  whose base class is in it). Testing all of them would mean hundreds of sites for one changed line.
- When anything that every source goes through changed, `core`, `util`, the contract, the build itself, 30
  sources taken from all of them are tested as well.
- The samples are picked by the commit, so running again tests the same ones.
- Sources marked `@Broken` are left out.
- A change that leaves nothing to test, a deleted source or one that is only in broken sources, is listed as
  untested and makes the check red.
- If nothing that goes into the parsers changed, nothing is tested and the check is green.

What a source "builds on" is its own file and the files of its base classes. A change in one source's file that
another source happens to use is tested through the first one only.

Many sites sit behind Cloudflare, which turns away GitHub's machines whatever the parser does. Expect a red
check for those, and a maintainer merging by hand after trying the source in the app.

To see which sources a branch changes without opening a pull request:

```
./gradlew selectParserTests -PcomparedWith=$(git merge-base origin/master HEAD)
```

The sources that changed themselves are the ones the pull request will test. The samples for shared files are
picked by the commit, so the pull request's own may be other sources.

## Why somebody has to read it

The tests tell that a parser works. They cannot tell what else it does: a parser is code that runs inside the app,
with the app's network access and the cookies of every source the user is signed in to. A parser that loads its
manga list correctly and also sends those cookies somewhere passes every test. So does one that simply returns
made-up results without touching its site. Passing tests are a reason not to check by hand that a parser works,
and never a reason to merge code nobody has read.

Things to look for besides the parser itself: anything outside `src/main/kotlin/.../parsers/site/`. The build,
the tests, `.github/` and `src/contract` are what make the checks mean something, and a pull request that changes
them can rewrite the check it has to pass.

## What the workflows are allowed

| Workflow | Runs the pull request's code | Token | Secrets |
|---|---|---|---|
| `pull-request.yml` | yes | read only | none |
| `publish-parsers-bundle.yml`, checking job | what is on `master`, visiting no site | read only | none |
| `publish-parsers-bundle.yml`, confirming job | no: it only waits for a reviewer | read only | none |
| `publish-parsers-bundle.yml`, signing job | **no**: the parsers are compiled, never run | the release | the signing key, on `master` only |

No workflow here merges anything, and none is set off by a pull request with a token that can write (there is no
`pull_request_target`). Merging is done by GitHub, for whoever with write access asked for it.

Code of the parsers is only ever run where there is nothing to take: in the job without secrets. The job that holds
the signing key compiles and packs them, and the key is handed to the signing step alone.

## Setting it up, once

In the repository settings on GitHub:

1. **General → Pull Requests**: turn on *Allow auto-merge*.
2. **Rules → Rulesets → New branch ruleset**, for the default branch, enforcement *Active*:
   - *Require status checks to pass*, and add `Parser tests` and `Parsers bundle` (they can be typed in before
     they have ever run), with *GitHub Actions* as their source where the page asks for one.
   - Bypass list: *Repository admin*, so that a maintainer can still merge by hand when a check is red for a
     reason that is not the pull request's, and push to `master` directly.

   Without this ruleset *Enable auto-merge* is not offered: there would be nothing for it to wait for.
3. **Actions → General → Fork pull request workflows**: choose who has to be approved before their pull request
   runs anything on this repository's runners. *Require approval for first-time contributors* lets people who
   have had a pull request merged here before be tested without a click; *all outside collaborators* (the page
   may say *external contributors*) asks for a click every time. Either way the run has no secrets and a
   read-only token.

## Good to know

- Auto-merge that a maintainer turned on is turned off by GitHub when somebody without write access pushes to
  the pull request again: the new commits have not been read.
- **It is not turned off when somebody with write access pushes again.** What they add is merged with the
  rest, and counts as merged by whoever turned auto-merge on. If that was the owner, it is published with
  nobody asked and nobody having read it. For a pull request by somebody with write access, turn auto-merge
  on only once it is final, or turn it off and on again after reading what was added. The ruleset option
  *Require approval of the most recent reviewable push* closes this, at the price of an approval on every
  pull request.
- Merging is publishing. A merge that changes the parsers starts **Publish parsers bundle** by itself, and that
  run tests nothing against the sites: all of `master` goes out as it is. When GitHub names the owner as the
  one who merged, nobody else is asked and it is out within minutes. For any other name, a bot's included,
  the run waits for a reviewer first.
