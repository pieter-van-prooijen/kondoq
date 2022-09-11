# kondoq

Using the clj-kondo analysis tool, scan github open source projects for var usages and store these in an sqlite
 database.
Expose an API and corresponding webapp for querying this database, together with a project upload section. 
Shows the var usage (e.g. function invocation or reference) in its containing defn.

Related work: dewey (see https://blog.phronemophobic.com/dewey-analysis.html )
Kondoq is more geared towards showing var usage in its context.

## TODO:
- How does the UI and performance hold up with a large number of projects (> 10)?. 
- give preference to "well-liked" repositories in results? (e.g. retieve and sort by the number of github stars)
- etag matching on github doesn't work with oauth tokens, as the token differs for each upload?
  Yes: the response has a Vary header which contains "Authorization" when making an authorized request. This means
  cached content won't help reduce the number of GitHub api requests unless the using fixed application tokens.

- a better way to skip certains files for analysis (like duplicate namespaces), see the skip-blob? predicate in
  github.clj

- determine project main branch instead of assuming "master"

- Does Hikari allows a way to execute multiple pragmas upon connection creation? Needs a complicated work-around at
  the moment to initialize sqlite correctly. Perhaps sqlite/xerial needs a different approach to keep the
  connection open between different jdbc calls.

- clojure style guide compliance, consistent naming of "fetch-", "search-" etc. (server done, do client next)
- more tests
- split in read/write databases (sqlite: file:bla.sqlite?mode=ro ) for later move to single writer with multiple
  reader instances?
  (note that read-only replicas in litestream are not working and wil be replaced with new implementation)
- strip leading whitespace when code context is collapsed?

## Security
- does a "dangeriously set inner html" in code/error messages. Uploading a project with malicious
  javascript might cause problems? (*fixed* uses highlight.js to detect malicious code and shows the html error
  message only when debugging)

## BUGS:
- entering a non-qualified symbol in the search field and pressing enter will give a malli error in the console.


## Getting Started

### Server

Requires the  clojure-cli tools (version 1.11+)

Clone the repository and create the following files:
- (optional) copy the provided github-oauth-sample.edn file to github-oauth-dev.edn, edit this later when enabling
  oauth using the 'config' system property.

Compile and run the tests:
```sh
$ clj -X:test
```
Starting the api and html/js file server:
```sh
$ clj -M:dev -m kondoq.server
```

Keep this server running.

### Client
Assumes a working recent node / npm

install: shadow-cljs via npm

Install the packages in package.json:
```sh
npm install
```

Compiling the shadow-cljs build:
```sh
$ npx shadow-cljs compile app
```

Compile the tests:

```sh
$ npx shadow-cljs compile browser-test
```

See http://localhost:8281/index.html to run the tests and see the test results

### Trying it Out

Visit http://localhost:3002/index.html to see the web-app

Add a github project via the projects tab (using a personal access token) and search using the search tab. After
typing three characters a popup list appears with potential matches, selectable by mouse.

## Development with CIDER (v1.5.0+)

See the .dir-locals.el file in the project root which configures the various cider parameters. You might need to
mark some variables as "safe to use" upon the first load.

To get both a clj and cljs repl with proper switching per source file use the following procedure:

cider-jack-in-clj  (C-c M-s or C-c C-x j j), use "clojure-cli" as the command.

open the dev/user.clj file and execute the integrant (go) command (e.g. using C-x C-e), this will start the backend
with the "dev" profile in deps.edn. Logging is on stdout in the cider repl buffer.

cider-jack-in-cljs (C-c M-S or C- C-x j s) (note the upper-case S) , say "y" to the "are you sure" question and use
"shadow-cljs" as the command to run. This will start shadow-cljs on the "app" build.

Open or reload the app on http://localhost:8280 , the cljs repl should now work.

Cider will merge the two repls in a single session because of cider-merge-sessions variable has the 'project
value. This single session enables correct repl switching behaviour.
See also https://docs.cider.mx/cider/usage/managing_connections.html#adding-repls-to-a-session and the
.dir-locals.el file in the project.

Running cljs tests inside cider is currently not supported out of the box. Use the run-test function to execute the
tests in the current namespace, which output printed in the cljs repl window.

## Production Build / Install

Make the uber jar (from the build.clj), will also create the production javascript:
```sh
$ clj -T:build uber
```

Run the jar with:
```sh
java -Dconfig=github-oauth-dev.edn -jar target/<path-to-jar>
```
Copy the jar in /target to the server, clean the database if a schema change happened.

### Batch Upload
There's an import-batch function defined in core.clj, which allows for off-line imports of projects. Invoke this
with:
```sh
clj -X:import-batch \
    :token '"<your github access token goes here>"' \
    :urls '[

"https://github.com/metosin/jsonista"
... other urls go here

]'
```
### Project Overview

Uses the following technologies:

- clj-kondo
- re-frame
- next.jdbc with the xerial sqlite driver
- honeysql
- highlight.js
- integrant
- ring with jetty
- malli
- jsonista
- clj-http
- reitit

The API request and response bodies are in edn.

GitHub oauth config is an edn file specified by the "config" system property or environment variable, containing
the url, secret etc. for accessing the GitHub oauth login page.
It's probably easier to get an application token and use that to access the GitHub api.

It has the following form (github supplies and id and secret when registering an oauth application):
{:github-client-id "<client-id>"
 :github-client-secret "<client-secret>"
 :github-callback-uri "http://localhost:8280/oauth-callback"}

By default, the server will use  "github-oauth-dev.edn" as a file (register with github create this file yourself).

#### Generic re-frame links
* Architecture:
[Single Page Application (SPA)](https://en.wikipedia.org/wiki/Single-page_application)
* Languages
  - Front end is [ClojureScript](https://clojurescript.org/) with ([re-frame](https://github.com/day8/re-frame))
* Dependencies
  - UI framework: [re-frame](https://github.com/day8/re-frame)
  ([docs](https://github.com/day8/re-frame/blob/master/docs/README.md),
  [FAQs](https://github.com/day8/re-frame/blob/master/docs/FAQs/README.md)) ->
  [Reagent](https://github.com/reagent-project/reagent) ->
  [React](https://github.com/facebook/react)
* Build tools
  - CLJS compilation, dependency management, REPL, & hot reload: [`shadow-cljs`](https://github.com/thheller/shadow-cljs)
  - Test framework: [cljs.test](https://clojurescript.org/tools/testing)
  - Test runner: [Karma](https://github.com/karma-runner/karma)
* Development tools
  - Debugging: [CLJS DevTools](https://github.com/binaryage/cljs-devtools),
  [`re-frame-10x`](https://github.com/day8/re-frame-10x)
  - Emacs integration: [CIDER](https://github.com/clojure-emacs/cider)
  - Linter: [clj-kondo](https://github.com/borkdude/clj-kondo)

#### Directory structure

* [`/`](/../../): project config files
* [`.clj-kondo/`](.clj-kondo/): lint config and cache files (cache files are not tracked; see
[`.gitignore`](.gitignore))
* [`dev/`](dev/): source files compiled only with the [dev](#running-the-app) profile
  - [`user.cljs`](dev/cljs/user.cljs): symbols for use during development in the
[ClojureScript REPL](#connecting-to-the-browser-repl-from-a-terminal)
* [`resources/public/`](resources/public/): SPA root directory;
[dev](#running-the-app) / [prod](#production) profile depends on the most recent build
  - [`index.html`](resources/public/index.html): SPA home page
    - Dynamic SPA content rendered in the following `div`:
        ```html
        <div id="app"></div>
        ```
    - Customizable; add headers, footers, links to other scripts and styles, etc.
  - Generated directories and files
    - Created on build with either the [dev](#running-the-app) or [prod](#production) profile
    - `js/compiled/`: compiled CLJS (`shadow-cljs`)
      - Not tracked in source control; see [`.gitignore`](.gitignore)
* [`src/kondoq/`](src/kondoq/): SPA source files (ClojureScript,
[re-frame](https://github.com/Day8/re-frame))
  - [`core.cljs`](src/kondoq/core.cljs): contains the SPA entry point, `init`
* [`test/kondoq/`](test/kondoq/): test files (ClojureScript,
[cljs.test](https://clojurescript.org/tools/testing))
  - Only namespaces ending in `-test` (files `*_test.cljs`) are compiled and sent to the test runner
* [`.github/workflows/`](.github/workflows/): contains the
[github actions](https://github.com/features/actions) pipelines.
  - [`test.yaml`](.github/workflows/test.yaml): Pipeline for testing.


### Editor/IDE

Use your preferred editor or IDE that supports Clojure/ClojureScript development. See
[Clojure tools](https://clojure.org/community/resources#_clojure_tools) for some popular options.

### Environment Setup

1. Install [JDK 8 or later](https://openjdk.java.net/install/) (Java Development Kit)
2. Install [Node.js](https://nodejs.org/) (JavaScript runtime environment) which should include
   [NPM](https://docs.npmjs.com/cli/npm) or if your Node.js installation does not include NPM also install it.
3. Install [Chrome](https://www.google.com/chrome/) or
[Chromium](https://www.chromium.org/getting-involved/download-chromium) version 59 or later
(headless test environment)
    * For Chromium, set the `CHROME_BIN` environment variable in your shell to the command that
    launches Chromium. For example, in Ubuntu, add the following line to your `.bashrc`:
        ```bash
        export CHROME_BIN=chromium-browser
       ```
4. Install [clj-kondo](https://github.com/borkdude/clj-kondo/blob/master/doc/install.md) (linter)
5. Clone this repo and open a terminal in the `kondoq` project root directory
6. (Optional) Setup [lint cache](https://github.com/borkdude/clj-kondo#project-setup):
    ```sh
    clj-kondo --lint "$(npx shadow-cljs classpath)"
    ```
7. Setup
[linting in your editor](https://github.com/borkdude/clj-kondo/blob/master/doc/editor-integration.md)

### Browser Setup

Browser caching should be disabled when developer tools are open to prevent interference with
[`shadow-cljs`](https://github.com/thheller/shadow-cljs) hot reloading.

Custom formatters must be enabled in the browser before
[CLJS DevTools](https://github.com/binaryage/cljs-devtools) can display ClojureScript data in the
console in a more readable way.

#### Chrome/Chromium

1. Open [DevTools](https://developers.google.com/web/tools/chrome-devtools/) (Linux/Windows: `F12`
or `Ctrl-Shift-I`; macOS: `⌘-Option-I`)
2. Open DevTools Settings (Linux/Windows: `?` or `F1`; macOS: `?` or `Fn+F1`)
3. Select `Preferences` in the navigation menu on the left, if it is not already selected
4. Under the `Network` heading, enable the `Disable cache (while DevTools is open)` option
5. Under the `Console` heading, enable the `Enable custom formatters` option

#### Firefox

1. Open [Developer Tools](https://developer.mozilla.org/en-US/docs/Tools) (Linux/Windows: `F12` or
`Ctrl-Shift-I`; macOS: `⌘-Option-I`)
2. Open [Developer Tools Settings](https://developer.mozilla.org/en-US/docs/Tools/Settings)
(Linux/macOS/Windows: `F1`)
3. Under the `Advanced settings` heading, enable the `Disable HTTP Cache (when toolbox is open)`
option

Unfortunately, Firefox does not yet support custom formatters in their devtools. For updates, follow
the enhancement request in their bug tracker:
[1262914 - Add support for Custom Formatters in devtools](https://bugzilla.mozilla.org/show_bug.cgi?id=1262914).

## Development

### Running the App

Start a temporary local web server, build the app with the `dev` profile, and serve the app,
browser test runner and karma test runner with hot reload:

```sh
npm install
npx shadow-cljs watch app
```

Please be patient; it may take over 20 seconds to see any output, and over 40 seconds to complete.

When `[:app] Build completed` appears in the output, browse to
[http://localhost:8280/](http://localhost:8280/).

[`shadow-cljs`](https://github.com/thheller/shadow-cljs) will automatically push ClojureScript code
changes to your browser on save. To prevent a few common issues, see
[Hot Reload in ClojureScript: Things to avoid](https://code.thheller.com/blog/shadow-cljs/2019/08/25/hot-reload-in-clojurescript.html#things-to-avoid).

Opening the app in your browser starts a
[ClojureScript browser REPL](https://clojurescript.org/reference/repl#using-the-browser-as-an-evaluation-environment),
to which you may now connect.

#### Connecting to the browser REPL from Emacs with CIDER

Connect to the browser REPL:
```
M-x cider-jack-in-cljs
```

See
[Shadow CLJS User's Guide: Emacs/CIDER](https://shadow-cljs.github.io/docs/UsersGuide.html#cider)
for more information. Note that the mentioned [`.dir-locals.el`](.dir-locals.el) file has already
been created for you.

#### Connecting to the browser REPL from VS Code with Calva

See the [re-frame-template README](https://github.com/day8/re-frame-template) for [Calva](https://github.com/BetterThanTomorrow/calva) instuctions. See also https://calva.io for Calva documentation.


#### Connecting to the browser REPL from other editors

See
[Shadow CLJS User's Guide: Editor Integration](https://shadow-cljs.github.io/docs/UsersGuide.html#_editor_integration).
Note that `npm run watch` runs `npx shadow-cljs watch` for you, and that this project's running build ids is
`app`, `browser-test`, `karma-test`, or the keywords `:app`, `:browser-test`, `:karma-test` in a Clojure context.

Alternatively, search the web for info on connecting to a `shadow-cljs` ClojureScript browser REPL
from your editor and configuration.

For example, in Vim / Neovim with `fireplace.vim`
1. Open a `.cljs` file in the project to activate `fireplace.vim`
2. In normal mode, execute the `Piggieback` command with this project's running build id, `:app`:
    ```vim
    :Piggieback :app
    ```

#### Connecting to the browser REPL from a terminal

1. Connect to the `shadow-cljs` nREPL:
    ```sh
    lein repl :connect localhost:8777
    ```
    The REPL prompt, `shadow.user=>`, indicates that is a Clojure REPL, not ClojureScript.

2. In the REPL, switch the session to this project's running build id, `:app`:
    ```clj
    (shadow.cljs.devtools.api/nrepl-select :app)
    ```
    The REPL prompt changes to `cljs.user=>`, indicating that this is now a ClojureScript REPL.
3. See [`user.cljs`](dev/cljs/user.cljs) for symbols that are immediately accessible in the REPL
without needing to `require`.

### Running Tests

Build the app with the `prod` profile, start a temporary local web server, launch headless
Chrome/Chromium, run tests, and stop the web server:

```sh
npm install
npm run ci
```

Please be patient; it may take over 15 seconds to see any output, and over 25 seconds to complete.

Or, for auto-reload:
```sh
npm install
npm run watch
```

Then in another terminal:
```sh
karma start
```

### Running `shadow-cljs` Actions

See a list of [`shadow-cljs CLI`](https://shadow-cljs.github.io/docs/UsersGuide.html#_command_line)
actions:
```sh
npx shadow-cljs --help
```

Please be patient; it may take over 10 seconds to see any output. Also note that some actions shown
may not actually be supported, outputting "Unknown action." when run.

Run a shadow-cljs action on this project's build id (without the colon, just `app`):
```sh
npx shadow-cljs <action> app
```
### Debug Logging

The `debug?` variable in [`config.cljs`](src/cljs/kondoq/config.cljs) defaults to `true` in
[`dev`](#running-the-app) builds, and `false` in [`prod`](#production) builds.

Use `debug?` for logging or other tasks that should run only on `dev` builds:

```clj
(ns kondoq.example
  (:require [kondoq.config :as config])

(when config/debug?
  (println "This message will appear in the browser console only on dev builds."))
```

## Production

Build the app with the `prod` profile:

```sh
npm install
npm run release
```

Please be patient; it may take over 15 seconds to see any output, and over 30 seconds to complete.

The `resources/public/js/compiled` directory is created, containing the compiled `app.js` and
`manifest.edn` files.
