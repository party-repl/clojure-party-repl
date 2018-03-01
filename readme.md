# clojure-repl

Clojure REPL for Atom written in ClojureScript with full Teletype support. Let's party! 😄


## Development

To make development easier with hot code reload and cljs repl, let's use shadow-cljs.

Install Shadow CLJS node package inside one of $PATH

```
npm install shadow-cljs
```

Also, open a test project in Dev Mode by clicking View -> Developer -> Open In Dev Mode. This allows Shadow CLJS to connect to Atom's JS runtime.

## Compiling, reloading and repling in

1. Compile using one of the methods below

To compile with a self-reloading loop with repl, use:

```
shadow-cljs watch app
```

To compile without a self-reloading loop, use:

```
shadow-cljs compile app
```

To compile for release (`:simple` optimizations), use
```
shadow-cljs release app
```

2. Repl into the running project

From a different terminal, run:

```
$ shadow-cljs cljs-repl app
```

You can exit the repl by typing `:repl/quit`


## Link the plugin to Atom

After you have compiled the plugin, go into the `plugin/` folder and run

```
apm install
apm link
```

You can now use clojure-repl plugin inside Atom as you develop it inside Atom!


In order to export functions to be used as atom commands, add them to `:exports` inside `shadow-cljs.edn` like this:

```
:foo clojure-repl.core/foo
:bar clojure-repl.core/bar
```


## License
Copyright 2018
Distributed under the Eclipse Public License, the same as Clojure.
