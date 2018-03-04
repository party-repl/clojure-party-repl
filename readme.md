# clojure-repl

Clojure REPL for Atom written in ClojureScript with full Teletype support for pair programming. Let's party! 😄

![Screenshot](readme.png)


## How to use this
TODO


## How it works
TODO


## Development
Wanna help polish this turd? Sweet! This package is built using shadow-cljs to make development easier supporting both:

- Hot code reloading. As you change the plugin's code, it will be dynamically reloaded so there's no need to refresh Atom.
- A full ClojureScript REPL into Atom, so you can REPL into the package and even inspect objects inside Atom.


### Install Shadow CJLS
First, install the Shadow CLJS node package somewhere on your $PATH:

```
npm install shadow-cljs
```

### Compiling our CLJS with auto code-reloading
Compile using one of the methods below:

To compile with an auto code-reloading loop with REPL, use:

```
shadow-cljs watch app
```

To compile without a code-reloading loop, use:

```
shadow-cljs compile app
```

To compile for release with `:simple` optimizations, use:

```
shadow-cljs release app
```

The first time you run shadow-cljs it will ask you to install stuff into the
project directory. This is ok, it's all blocked by `.gitignore`.

After running this you should see compiled JS dropped into the standard `lib` folder
inside the `plugin/` directory. The `plugin/` directory is ultimately what gets
published to Atom. The `lib/` folder is not under version control since it's
a byproduct of the build process.

When using the shadow-cljs' `watch` mode, most changes will be automatically
reloaded such as changes to ClojureScript functions and vars, however changes to menus and
things on Atom's side will still require a refresh with View -> Developer -> Reload Window.


### Linking the plugin to Atom
After you compiled the plugin, go into the `plugin/` folder and run:

```
apm install
apm link
```

Next, restart Atom so it will notice the newly linked package. We just created a symlink
from `~/.atom/packages/clojure-repl` to your project path (this is why you must be
inside the `plugin/` directory when creating the link).

After restarting Atom, you should see the clojure-repl plugin installed and be able to use it.


### REPLing into the running project
Now that the package is installed, check the `shadow-cljs watch app` output and look for a message saying
`"JS runtime connected"`. This means it made contact with our package running in Atom. Now we can
REPL in. This can be finicky, if you don't see the message, try reloading Atom again.

Next, while shadow-cljs is running, in another terminal run:

```
$ shadow-cljs cljs-repl app
```

This will connect you directly into the plugin so you can live develop it. You can exit the REPL by typing `:repl/quit`

Once we add CLJS support, you'll be able to REPL into the package using itself. 🐵


### Testing and hacking on the clojure-repl
Now that we've got dynamic code-reloading and a ClojureScript REPL into Atom,
let's take this thing for a spin.

The easiest way to work on the plugin is to open a new Clojure project
in Atom in Dev Mode. If you don't have a dummy project around `lein new app test-project`
will make one. Open the new project by going to View -> Developer -> Open In Dev Mode,
and then open the development console View -> Developer -> Toggle Developer Tools to
show any possible errors preventing the plugin from starting.


### Adding new functionality to clojure-repl
The best place to start hacking is `core.clj`. This is where all
the public functions that do interesting stuff are defined and exported.

If you want to add something cool, follow the pattern in `core.clj` and
add your function to `shadow-cljs.edn` too.

Exported functions can be linked to keybindings and menu items, checkout the standard
`keymaps` and `menus` directories.


## Roadmap

### Core features
- [ ] Support for Boot
- [ ] Support connecting into remote Socket REPLs
- [ ] ClojureScript support
- [ ] Unrepl support

    Support nREPL, but all advanced features like type ahead information, getting docstrings and namespace reflection will only work with unrepl. First class nREPL support only extends to executing commands and generating great stacktraces.
- [ ] Add line number and file metadata to stacktraces.


### Pair programming / Teletype support
- [ ] Use Teletype to send messages between the host and guest.
- [ ] Sync the REPL history between the host and the guest.
- [ ] Find a better way to link the REPL editors between the host and guest. Right now they are just linked by the title like “REPL Entry”, but this limits us to just 1 REPL over Teletype at a time.
- [ ] Find a way to gracefully open both the input-editor and output-editor (history) editors at once on the guest’s side when the host clicks in either one. For example trick Teletype to sending both editors over to the guest at the same time.


### Using multiple REPLs simultaneously support
- [ ] When executing code from a file, find which project the file is in, and run the code in the REPL that corresponds to that project. Otherwise, fall back to the last used REPL. How can we do this on the guest side which doesn't have access to all the files?
- [ ] Add the project name to the title of the REPL editors
- [ ] If multiple REPLs are open, instead of choosing a REPL by project, add an option to execute code in the REPL that is currently visible in the editor. For example, if you have multiple REPLs tabs open, it will choose the one that's visible and not in the background. Or if multiple REPLs are visible on screen (or all of them not on screen) fall back to the last recently used REPL.


### User interface improvements
- [ ] Create a new kind of Pane which holds both the REPL input-editor and output-editor.
- [ ] Add buttons to REPL Pane like "Execute" and "Clear history".
- [ ] Make the "Execute" button dynamically switch to "Cancel" when a long running command is executed (this should work seamlessly on the guest side too).
- [ ] Create a custom Atom grammar for the REPL history editor, ideally this would draw horizontal lines between each REPL entry.
- [ ] Make the output-editor (history) read-only.


### OS support
- [x] Linux support
- [ ] Windows support, probably not going to do it. PRs welcome.


### Polish
- [ ] Add support for multiple Atom projects. When starting a REPL either start it in the project for the current file, or prompt the user and ask which file.
- [ ] Update the our namespace detection regex to allow for all valid characters in a namespace: include $=<>_
- [ ] Only allow one command to execute at a time. If we execute code in the REPL, either from a file or directly from the input-editor, it should show a spinner in the REPL UI and ignore the command.
- [ ] Compile out a release version with advanced optimizations and all of the REPL/dynamic reloading shadow-cljs features turned off.


### Errors
- [ ] Fix error “Pane has been destroyed at Pane.activate” when REPL TextEditor is the only item in a Pane.
- [ ] Fix error “TypeError: Patch does not apply at DefaultHistoryProvider.module.exports.DefaultHistoryProvider.getSnapshot <- TextBuffer.module.exports.TextBuffer.getHistory”


### Possible future ideas
- [ ] Stop multiplexing stdout/stderr and REPL results through the output-editor (history). Add a 3rd collapsable editor which
is dedicated to stdout/stderr. Additionally, add an option to
hook into all stdout/err streams on a remote socket REPL, so
all output can be seen even from other threads.
- [ ] Show results next to the code in the file that generated them like proto-repl.
- [ ] Sayid debugging support
- [ ] The ability to add breakpoints or wrap seamless print statements around blocks of code. These would be a first-class UI feature, imagine a purple box wrapped around code that would print it out, every time


## License
Copyright 2018 Tomomi Livingstone.
Distributed under the Eclipse Public License, the same as Clojure.
