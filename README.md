# Kondoq

Using the clj-kondo analysis tool, scan github open source projects for var usages and store these in an sqlite
 database.
Expose an API and corresponding webapp for querying this database, together with a project upload section. 
Shows the var usage (e.g. function invocation or reference) in its containing defn.

Related work: dewey (see https://blog.phronemophobic.com/dewey-analysis.html )
Kondoq is more geared towards showing var usage in its context.

## TODO:
- How does the UI and performance hold up with a large number of projects (> 10)?. 
- give preference to "well-liked" repositories in results? (e.g. retieve and sort by the number of github stars)
  (partially in)
- etag matching on github doesn't work with oauth tokens, as the token differs for each upload?
  Yes: the response has a Vary header which contains "Authorization" when making an authorized request. This means
  cached content won't help reduce the number of GitHub api requests unless the using fixed application tokens.

- a better way to skip certains files for analysis (like duplicate namespaces), see the skip-blob? predicate in
  github.clj

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
$ npm install
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
$ java -Dconfig=github-oauth-dev.edn -jar target/<path-to-jar>
```

Copy the jar in /target to the server, clean the database if a schema change happened.

### Batch Upload
There's an import-batch function defined in core.clj, which allows for off-line imports of projects. Invoke this
with:

```sh
$ clj -X:import-batch \
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

See [re-frame.md] for the generic re-frame project documentation.
