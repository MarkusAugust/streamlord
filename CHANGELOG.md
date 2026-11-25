# Changelog

All notable changes to Streamlord are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### SDK

- In `interpolate { }`, a value marked with `+` in the text of a `Trusted` is escaped where it
  lands, whether the `Trusted` is marked or not: `${+Trusted("Hi <i>${+name}</i>")}`, and a `val`
  set to one and marked later. A value marked in an `Html` the placeholder form built, in its
  markup or in a `Trusted` handed to it, is escaped in element text, whether the `Html` is marked or
  written unmarked: `${+interpolate("<b>%s</b>", Trusted("<i>${+name}</i>"))}`. A marked `Html` is
  then judged as any `Html` is, and a `Trusted` marked in it is built as one. Before, the marked form
  wrote the mark's note, block id and all, into the page and lost the value, and in the unmarked
  form a `Trusted` marked there could move a value the placeholder form had escaped for element
  text into a `<script>`, an `onclick` or a `javascript:` URL. A refusal's `index` counts the
  marked `Trusted` or `Html` too, as the placeholder form counts a `Trusted` value (#338, #352,
  #354).
- **Breaking:** in `interpolate { }`, a value marked in an `Html` the placeholder form built is
  refused anywhere but element text, written unmarked too:
  `interpolate { "<p>${interpolate("<b title=\"${+name}\">b</b>")}</p>" }` throws, where it
  escaped `name` into `title`. The block cannot know the `data-*` prefixes the `Html` was built
  with, so it cannot tell such a place from a Datastar expression. Hand the value to that
  placeholder form as one of its own, `interpolate("<b title=\"%s\">b</b>", name)` (#354).
- **Breaking:** `interpolate` refuses a value that holds a mark of a block being built, where it
  would escape the value, with an `IllegalArgumentException`: `+name` handed to the placeholder
  form, `interpolate("<b>%s</b>", +name)`, a string with a mark in it, `"Hi ${+name}"`, and an
  `Html` holding one that it escapes into an attribute, where the value was lost without a word
  and the block's id written into the page. Pass the value itself,
  `interpolate("<b>%s</b>", name)`, or mark it where it stands in the block's markup. Text from
  outside never stops the page: a mark is refused only while the block that made it is being
  built (#358).
- **Breaking:** `interpolate { }` refuses markup that holds a mark of another block still being
  built, with an `IllegalArgumentException`: a mark of an outer block written in a block inside it,
  `interpolate { val m = +name; interpolate { "<p>$m</p>" } }`, where the value was lost and the
  outer block's id written into the page. Mark the value in the block it is written in (#360).
- streamlord-spring: two test helpers in `io.github.markusaugust.streamlord.spring.test` read a
  whole stream through MockMvc, a `suspend` controller's too. `MockMvcTester.datastar(method, uri,
  signals)` sends the request as Datastar's client does, and `mockMvc.datastarRequestFactory()`
  gives `RestTestClient.bindToServer(...)` the same reading. An answer still open after
  `asyncTimeout`, ten seconds by default, is stopped and fails. `datastar` replaces the helper the
  testing page gave to copy. See
  [Testing](https://streamlord.sobernetics.no/testing/#through-the-controller-with-mockmvctester).
- **Breaking:** streamlord-html and streamlord-html-pro refuse a second value for an attribute the
  element already has, with an `IllegalStateException` as the page renders, where the second call
  replaced the first without a word: `dataSignals("a" to true)` and then `dataSignals("b" to true)`
  wrote `b` alone, and `$a` was never declared. An element holds one attribute of a name, so write
  both in one call, `dataSignals("a" to true, "b" to true)`, or one signal a call with
  `dataSignals("a", "true")`. The same holds for two `dataOnClick`, two `dataShow` and every other
  helper. Calls that write different names, such as `dataSignals(...)` beside
  `dataSignals(..., ifMissing = true)`, are written as before, and so is the same value given again.
  A function that wrote a default for its caller to replace with a second call now takes the value
  as a parameter. A helper of your own writes through the same check with
  `DatastarAttributes.write(tag, "tooltip", value)`, which writes `data-tooltip`.

### The analysis

- A literal in a `Trusted(...)` marked in `interpolate { }`, alone, as a branch of a marked `if`,
  or handed to the placeholder form whose `Html` is marked, is read as the block's markup, as one
  in a `Trusted` written unmarked is: `${+name}` in it is no longer hinted, and a value Kotlin
  fills into it is warned and offered the mark with `+` (#338, #352). A value marked in the
  format of the placeholder form in the block, or in a `Trusted`'s own tags handed to it, is
  refused outside element text as core refuses it, and no mark is offered there (#354).
- The placeholder form written in a template's code, `"<p>${interpolate("<b>%s</b>", name)}</p>"`,
  is judged as the placeholder form, where its format was read as free HTML and a `%s` in a
  Datastar attribute was a syntax error. In `interpolate { }`, a value marked in a `Trusted` handed
  to it is judged where the `Trusted` lands, and a `+Trusted(...)` in its format where it stands.
  An `Html` of it written unmarked into a quoted attribute, holding a value marked in it, is an
  error with the mark as its fix. A value marked in it is no longer offered as a `%s` value, which
  core would have written without it (#356).
- In `interpolate { }`, a mark handed to the placeholder form, `interpolate("<b>%s</b>", +name)`,
  or held in a string handed to it or marked, `"Hi ${+name}"`, is an error as core refuses it, or
  a warning where the value may be a number, with the `+` taken away as its fix. So is an `Html`
  of the placeholder form holding a value marked in it, handed to a second placeholder form that
  writes it into an attribute (#358).
- In `interpolate { }`, a mark is followed wherever Kotlin keeps it in the value, as core refuses
  it: through a `val`, `val m = +name`, a second name, a string joined to it, `"Hi " + +name`,
  `?:`, `to`, `as Any`, a list, `toString()`, a branch of an `if` (a warning), and the `.text` of
  an `Html` of the placeholder form holding one. Such an `Html` handed on by name or in brackets is
  an error in an attribute, and in a `<textarea>`, a `<title>`, a comment, or where it leaves a tag
  or an element open, since core escapes it there as it reads it. A mark of an outer block written
  in a block inside it is an error, with the value marked in that block as its fix, and a warning
  where the inner block is a lambda kept for later. A list holding a mark is no longer offered
  written out, and a template holding the block's own mark no longer the `+` that would be refused
  (#360).
- A multi-dollar literal in a template's code, `"${interpolate { $$"<p>$m</p>" }}"`, opens its
  templates with its own dollars: `$m` there is text, where it was read as a template.
- A value may be null where the calls it ends in show it, as the Kotlin compiler types it:
  `s.takeIf { c }`, `q.let { v -> v }`, `q?.plus(s ?: "")` and `xs.first()` of a
  `List<String?>` no longer have the rewrite to `interpolate` preferred, since `interpolate`
  writes nothing where the template wrote `null`. Before, of the calls a value ends in, only a `?.`
  with no `?:` anywhere in the value made it one that may be null. The same reading decides
  whether `Trusted(...)` is offered for a value in element text or a `<style>` (#349).
- A value made of `?:` or brackets is judged as an `if` of the same values is. `${s ?: q}`
  with `q: String?` no longer has the rewrite to `interpolate` preferred, since `q` may be null
  and `interpolate` writes nothing where the template wrote `null`. An `Html` in brackets or on
  either side of `?:`, `interpolate("<p>%s</p>", k ?: g)`, is no longer warned as markup held as
  a String, and `a == b ?: c` is no longer warned, as `a == b` is not.
- `@Language("HTML")` and `// language=HTML` reach as far as the Kotlin plugin injects them. A
  marked function's returns count through `if`, `?:`, a sum and `trimIndent()`, and so do the
  literals a returned local is set to, alone or in a sum, `val s = "<b>" + x; return s`; a mark
  on a property no longer reaches its getter or a `by lazy { }` block. Attribute names are
  completed in a later literal of a sum, `"<div" + " data-on:click__"`. Before, a mark reached the
  next literal within 240 characters, wherever it stood. What the plugin marks and the analysis
  does not yet read, such as an assignment to a marked `var`, is listed in #347 (#311).
- A parameter marked `@Language("HTML")` is found by its place after a comparison among the
  parameters before it, spaced or not, `keep: (Int) -> Boolean = { it > 0 }` or `big: Boolean = 1<2`:
  an argument handed to it is markup. Before, the comparison moved the mark onto the first parameter.
  No comma in a type's brackets cuts a parameter in two either, `Map <String, Int>`,
  `Map<String, (x: Int) -> Unit>` or `Map<T, T & Any>`. The mark is read among the annotations before a parameter's
  name, so `@Language("HTML")` written in a default's text, `example: String = """@Language("HTML")"""`,
  marks nothing (#301, #302).
- A type's brackets are read once for the file, as Kotlin's grammar has a type's arguments, and every
  reading of a type uses that: a parameter list's commas, where a line ends, an annotation's type
  arguments, a declared type's arguments and the joined values of a string. A value whose type ends in
  `List<(Int) -> Unit>` or `Map<String, () -> Unit>` now ends where Kotlin ends it, a lambda's parameter
  typed `Map<String, Int>` is declared, an annotation in a type's brackets, `List<@A Html>`, is no part
  of the type read, and `inline fun <reified T>` and `val <T : Any> T.x` hold a type's brackets.
- The rest of the analysis reads annotations and type brackets the same way: one reading of an
  annotation for the file, Spring's routes and the rewrite's file header included, and type
  parameters and a handler's parameters cut by the type brackets. A lambda's parameter typed
  `Map<String, () -> Unit>` is declared, and a function whose type parameters hold a function type,
  `fun <T : (Int) -> Unit, R> f(r: R)`, has its parameters read. An annotation's arguments may follow
  blanks or a line break, `@RequestMapping ("/api")`, and so may a use-site target's name, `@file: JvmName("X")`,
  as Kotlin allows: `@Language ("HTML")` now marks. `@Composable () -> Unit` and `@Composable (() -> Unit)?`
  stay function types, and a long literal in an annotation, a query, is read to its end (#342).
- A function's return type is read past its type arguments, `List<Int>?`, `Map<String, () -> Unit>` and
  `List<List<List<Int>>>`, so the values it returns are read as Kotlin's parser reads them. A Spring
  handler parameter's annotation is read to its end, `@RequestParam(defaultValue = ")") q: String`
  (#343). A `@JsonNaming` in a comment no longer hides a naming strategy in the code, a modifiers lambda is
  read past a brace in a string or a comment, a constructor's annotations are read to their end, and a
  backend action's options lambda still being typed, `post("/x") { payloadExpr = "1"` with no closing brace,
  has its options read.
- A value in a literal handed to `Trusted`, `Trusted("Hi <i>$name</i>")`, is told what Trusted does
  with it: it vouches for all of its text, the value too. The advice is to build that markup with
  `interpolate { }` and mark the value, which the fix does, `Trusted(interpolate { ... }.text)`, and
  in element text the fix is not preferred, since Trusted says its text is markup. The fix that
  wrapped the value in `Trusted` once more is gone. The same holds where Trusted's argument is a sum
  that opens with a value or has a literal in a template.
- `Trusted` written with its package, `io.github.markusaugust.streamlord.core.domain.Trusted(...)`,
  or under an alias, `import ...Trusted as T`, is read as core's: its argument is markup, coloured
  and judged. VS Code colours the qualified name too; an alias it cannot see.
- A value in a string a `val` of `interpolate`'s block is set to is told where a mark belongs: in the
  block's markup, and not in a string that goes elsewhere, such as a log, where the `+` would be
  written into the log text. Where such a string goes only its uses tell, which the analysis cannot
  follow, so the value is warned of as before and the fix that marks it is no longer preferred.

- Long and unfinished text no longer stops the analysis of a file. A lambda with thousands of
  parameters, a type's `<` that never closes and a string that never closes each threw a stack
  overflow on the JVM, and the whole file got no answers. Thousands of unclosed annotations, and
  thousands of blank or comment lines after an operator, took seconds; they now take a fraction of
  one.

- Declarations are read as Kotlin's parser reads them in more shapes, and the check of values written
  into markup follows:
  - an annotation in backticks or with type arguments, `` @`A` `` or `@A<B<C>>`, no longer hides
    the `open`, `const` or `@Language("HTML")` beside it, nor the parameter after it;
  - an annotation with brackets or quotes in its arguments, `@Deprecated("m", ReplaceWith("x"))`
    or `@Named("a)b")`, no longer hides the parameter after it;
  - an annotation still being typed in a constructor, `@Foo(` with no `)`, no longer hides the
    properties after it;
  - a receiver in brackets, `fun ((Int) -> Unit).call(n: Int)`, is no parameter list, and a
    function without a name may have a receiver, `fun String.(): String = ...`;
  - a modifier's word that is an operand, `b +` then `open` on the next line, or an infix call,
    `b { } open value` or `b private const`, no longer marks the declaration after it;
  - a local `val` has no getter, so a call of `get()` after it is a call.

- What a comment writes defines and reads no signal: `// set("count", 1)`, a KDoc that mentions
  `` `toggle("open")` `` or `` `dataBind("name")` ``, `// <div data-signals:total="1">` and
  `// $count++` gave their names to completion, to navigation and to the checks of signals. A
  helper's name in a string is no call either. A call in a template's code, `"${set("count", 1)}"`,
  still is. A file with such a comment is no longer read twice in a module without the DSL.

- A helper's read in a template's code, `"${signal("shown")}"`, is a reference: Find Usages and
  rename find it, and a signal it names that nothing defines is unknown there too. The readings of
  signals already took it.

- A `}` or a quote in a comment in a template's code, `"${ a /* } */ }"`, or a `}` in a line comment
  before a line break, no longer ends the template, so what follows the literal is read as code
  again. Block comments nest there as Kotlin's do.

- A comment in a value written into markup, `val v = (/* all */ rows).joinToString("")` or
  `"${ (/* all */ rows).joinToString("") }"`, no longer keeps the editor from being asked about what
  the value is built on, so IntelliJ's type of `rows` decides whether it is markup, as it does
  without the comment.

- The build check is one line: `fun markup() = Analyzer().checkProject()` reads the project under
  `src/main` and fails with every finding that fails a build, each written as a compiler writes
  it. `checkProject(strict = true)` fails on what `failures(strict = true)` keeps. See
  [The analysis](https://streamlord.sobernetics.no/analysis/#a-test-over-your-own-sources).
- `analyzeProject` reads HTML and template files too, each as the editors read that kind of file,
  where it read `.kt` alone, and gives `unknown-signal` and `signal-never-declared` as the editors
  do. Both are warnings, which `failures()` does not keep. Hand it `src/main` where you gave
  `src/main/kotlin` to have the templates read; a test that already gives a folder with HTML in it
  can now fail on that HTML. It reads the template extensions the editors read by default, not
  ones added in an editor's settings. It also takes a `ProjectTree` in place of a folder, for a
  project that is not on a disk.
- **Breaking:** `ProjectIssue.file`, a `File`, is `ProjectIssue.path`, a `String`: `it.file` is
  `File(it.path)`. `format()` is as it was. `failures()` is source compatible; Java and compiled
  callers find it in `ProjectKt` where it was in `PlatformJvmKt`.
- **Breaking:** `Issue.code` is an `IssueCode`, an enum of every code the analysis gives, where it
  was a `String?`. Compare with `IssueCode.KOTLIN_INTERPOLATION`, and read the name as `code.id`;
  a template that writes `"$code"` still writes the name. Every issue has a code, so code that
  makes an `Issue` passes one. `format()` writes `[kotlin-interpolation]` as before.
- A Kotlin value in an HTML string is judged by what its name means in three places where it was
  judged by something else:
  - In a `catch` block, `$e` is the exception the `catch` names. It was read as a parameter or a
    value named `e` around the `try`: an `Int` of that name hid the hint, and a `String` made it a
    warning. With no other `e` around, nothing changes.
  - A lambda's parameters are found where a comma follows the last or a comment stands among them.
    They were not, so `$n` in such a lambda was judged as an unknown name, or as a value named `n`
    outside it.

  - A function gives what its own `return`s give. A `return` in a function declared inside it was
    counted as well, so a function that gives only markup was told of where one inside it returned
    text, and one that returns nothing was taken for markup where one inside it returned some.

  Where a name now means something else, a hint may become a warning or a warning a hint, and
  `failures(strict = true)` keeps warnings: a `String` parameter of a lambda with a comma after the
  last is a warning where it was a hint.
- `repeated-attribute`, a new warning in HTML, templates and HTML in Kotlin strings: a Datastar
  attribute the element already has, in any capitals, with another value. The browser keeps the first
  and drops the rest, so Datastar never reads them. A tag a template writes into is left alone, and
  so is one with a directive for a name, such as `@auth` or `#if(x)`.
- A class that is not `@Serializable` is read as Jackson writes it, where it turned the signal
  checks off for the whole project: a page declared from it with `toJsonValue`, a patch of it and a
  `readSignals<T>()` of it are judged by its public constructor properties, each by its name or its
  `@JsonProperty`, without one under `@JsonIgnore`, and the class is the one the file's package and
  imports name. A class with names elsewhere, in a body, a supertype, `@JsonNaming`,
  `@JsonIgnoreProperties`, `@JsonAlias` or `access`, and every class in a project that sets a naming
  strategy, in Kotlin code or in Spring's `spring.jackson.property-naming-strategy`, stays the codec's
  alone, as before. A patch of such a class of the project may set any signal, in both checks, as a
  page declared from one does; a patch of a class the project does not declare sets none. Where the
  project writes signals whose names are known only at run time, a page whose `data-signals` a
  template writes (`th:data-signals`, `data-signals="{{ signals | raw }}"`), `dataSignals(json)` or
  `attributes["data-signals"] = json` with JSON text, or a patch of JSON text, a Jackson class a
  handler reads is not judged, as it was not before; a `@Serializable` class and a lookup are.
- A `@Serializable` class written `@kotlinx.serialization.Serializable`, or with an annotation whose
  arguments hold parentheses beside it, is read as one, and so is a `@SerialName` or `@JsonProperty`
  with an annotation or a comment after it. A naming strategy in kotlinx's `Json { namingStrategy = ... }`
  now does the same for `@Serializable` classes, which were judged by names the codec did not write.
- **Breaking:** `SourceLanguage` has `SETTINGS`, for Spring's `application.yml`, `.yaml` and
  `.properties`, which `ProjectFiles.languageOf` names and `checkProject` and both editors read for a
  naming strategy. A `when` over `SourceLanguage` needs the branch. `SignalFacts.renamesSignals` says
  a file sets a strategy.
- A Datastar expression with template syntax in it is read around that syntax, where it was left
  out whole: `data-text="$cuont + {{ x }}"` in Pebble and `data-text="$cuont + ${x}"` in jte get
  `unknown-signal`, and go to definition, find usages and rename reach those reads in both
  editors. A read that the engine's output touches, such as `{{ p }}$a` or `$a{{ i }}`, is left to the
  template, and a construct that writes nothing, such as `{% if %}`, stops no read.
- `Fix.leads` says an editor that sorts fixes itself keeps the fix at the top though it is not
  preferred, as the fixes that mark a value with `+` are.
- `ProjectIndex` holds what a project's files give each other, for a tool that keeps a project in
  memory as an editor does: `put(path, text, language)` when a file changes, `remove(path)` when it
  is gone, and then the signals the project defines, its constants, ids and classes, and
  `signalsFor(path)` for `projectSignalIssues`. `put` and `remove` answer whether the other files
  are to be judged again. Both editors and `analyzeProject` keep their files in one, where each
  kept its own. `ProjectFiles.FILE_CAP` and `SIZE_CAP` name how much of a project an editor lists.
- `datastarStrings(source)` gives every string of a Kotlin file that is Datastar's, for a tool that
  colours or spells: each is an expression, a signal's name, the key of `attributes["data-..."]` or
  HTML, with its tokens and, for an expression, the prose in it, at the offsets of the source.
  `datastarName(name, prefix)` reads an attribute's name under the prefix given or either bundle's,
  and gives the tokens and the prose of a value written to it.
- `valueQuestions(source)` lists the values of a Kotlin file the analysis asks an editor about,
  each by the range it asks by, so an editor that resolves types answers for those and need not
  find the values itself. The parts of a value are among them where their type decides: the
  arguments of a helper, the branches of an `if`, what `joinToString` or `.text` is called on, and
  what a `val` or a function of the file is set to. Asked again with the answers it has, it lists
  what a declaration an answer placed in the file leads on to.
- **Breaking:** an editor answers through `Source.valueAnswer` with a `ValueAnswer`: the type the
  value resolves to, written with every class by its qualified name (`kotlin.Int?`,
  `kotlin.collections.List<com.acme.Row>`), whether it is a constant, and the declaration it names:
  where its name stands in the file, or its text when it is another file's. The analysis judges the
  value by these and reads the rest from the text, for every editor alike. `Source.valueFact` is
  gone, and `ValueFact`, `opensWithTag`, `isMarkup` and `writesItself` are no longer public.
- The analysis reads more of how a value is built, so VS Code says nothing of these where it gave
  a hint: a function of the file whose every result is markup, `fun badge() = "<b>new</b>"`, or
  that is marked `@Language("HTML")`, with a `prefix`, a `value =` or a use site such as `@param:`
  too; a `val` set to an HTML literal or to the `text` of an `Html`;
  `trimMargin()` on a literal in parentheses; a collection made with `setOf`, `sequenceOf` or
  `listOfNotNull`; and `"by " + author` where `author` is an `Html`. A helper with a lambda of
  options, `post("/save") { contentType = ContentType.FORM }`, is the author's own when each
  option is.
- A function or a `val` another may stand in for is not followed to what it gives: one that is
  `open` or `abstract`, a member of an interface, or an `override` in a class that is not final. A
  call that a function declared inside another, a value of the same name, or a member of another
  class of the file may mean is not followed either, nor is a function that takes markup by a
  parameter marked `@Language("HTML")`, whose argument nothing judges.
- A `@Language("HTML")` mark reaches the next literal after it past another annotation's
  arguments and past comments, quotes in them too, a mark written inside a comment marks
  nothing, a parameter's mark behind another annotation or a modifier (`private val`, `vararg`)
  reaches no later default, and no literal of an annotation's arguments is HTML:
  `@Language("HTML") @JvmName("x") fun f() = "Hey <b>…"` read `"x"` as the HTML string and not the
  function's literal.
- A value whose line ends in `!!`, `++`, `--`, a nullable type or type arguments closed on its
  line ends there, `val a = q!!`, where it ran on into the next line and took a getter after it
  for its own. A function's expression, or a loop's statement without braces, ends with its line
  too, where it ran to the end of the block around it: `fun size(n: Int) = pad(n)` inside another
  function kept its `n` in sight to the end of the outer one, so `$n` after it was read as the
  Int. A value, an expression and a statement go on past `if (...)`, `when (...)`, `for (...)`,
  `while (...)`, `try`, `else`, `as`, `as?`, `is`, `in` or an infix function's name (`a to`) at
  the end of a line, and into a `try`'s `catch` and `finally` however they are laid out; a line
  that opens with a literal, `"<p>$n</p>".also(::println)`, is a statement of its own. A loop's header is read whole, over several lines and with a lambda in
  it, and an `else` on the line after a loop's statement is the statement's own only where the
  statement has an `if` still without one.
- Parameters are read with their comments left out. With `a: String, // the a` on one line and
  `title: Int` on the next, `title` was no parameter: `$title` in the function was warned of, and a
  constructor property written so was not one other files could see. The same after an annotation
  followed by a comment. A `@Language("HTML")` inside a comment no longer marks the parameter after
  it, and a comma in a comment or a string no longer moves which parameter a mark is on. A comment between a
  function's parameters and its body no longer hides its declared type, so
  `fun badge(): String /* the label */ = "<b>x</b>"` is read as the String it is.
- What is written before a declaration is read once, as the file is: a mark, an `open`, a
  `private` or a `const` is seen behind another annotation, one with brackets in its arguments, a
  comment, a line break or any stretch of text, where each was looked for in the few characters
  before the keyword, and a mark in a comment is none, where `// @Language("HTML")` above a `val`
  marked it. `open` in `val isOpen = door.open` is a name, and no longer makes the `val` on the
  next line one another may stand in for, and `@[A B]` is passed over whole. A constructor behind
  an annotation with arguments, `class Row @Named("x") constructor(val id: String)`, declares its
  parameters. A getter is seen however far behind its `val` it stands, and neither a call of a
  function named `get` after a `val`, `get("/") { }` in a route, nor a function declared after it,
  `fun get()` of a `Supplier` or `operator fun get(i: Int)`, is a getter of it. A value that ends
  in a raw string closing with four quotes ends there, and a name in backticks declares nothing by
  the words in it, as a test's name with `val` in it did. A function's parameters are in sight in
  the `object` or the function without a name that its expression is, where `fun row(n: Int) =
  object : Row { ... "$n" }` warned of `$n`.
- A sum is markup where one part is and the others are judged where they stand, beside an HTML
  literal, or are plain literals: `"<b>" + q` and `q + "<b>"` say nothing of the whole, and `q`
  has its warning. `buildString { }` is markup only when every statement in it writes
  kotlinx.html, where one `appendHTML()` among other statements was enough.
- `${+row(name)}` in `interpolate { }` is a warning where `row` is a function of the file that
  gives markup as a `String`, which `interpolate` escapes.
- A declared `HashSet<Html>`, `MutableCollection<Trusted>`, `ArrayDeque<Html>` or another of
  Kotlin's and Java's collections is a collection of markup, as `List<Html>` is, so
  `rows.joinToString("")` over one says nothing.
- **Breaking:** `attributesIndexes`, `expressionOptions`, `signalNameStrings`, `htmlStringAt` and
  `tokenizeAttributeName` are no longer public, nor `AttributesIndex`, `ExpressionOption` and
  `SignalNameString`: `datastarStrings` and `datastarName` answer what they were put together for.

- A call under a comment that ends in a full stop is no longer taken for a call on a receiver:
  `increment("click-count")` below `// Count the click.` gets its `signal-name-hyphen` error, and
  the helpers that read signals, the options set to an expression, `Trusted(...)` and a function
  with a `@Language("HTML")` parameter are read there as they are elsewhere. So are a stream's
  calls, `patchSignals("{bad")` under `// Sends the update to the page.` gets its error, and a
  short helper such as `set(...)` in a module without the DSL. `string("query")` under
  `// Not one of the signals.` is no longer a read of the signal `query`. A `while` or a `catch`
  after `do { } // once more.` or `try { } // tried.` is now read as the loop's end or the handler
  it is, and `interpolate { }` under `// have fun` is read as the call it is, where it was taken
  for a function declared by the name.

### Editors

- VS Code no longer lets the colours of a Kotlin literal run on into the code after it. A literal
  whose HTML left something open, `"<lt-suggestion"`, `"<svg "`, `"<!-- note"`,
  `"<a href=\"" + url`, or whose script did, `"<script>console.log(\"x\")</script>"` and
  `executeScript("var a = \"x")`, handed its closing quote to the grammar inside it, and the lines
  after were coloured as attributes, a comment or JavaScript until something closed them. A literal
  that ends on its line now ends its HTML or JavaScript with it, and is coloured inside its quotes
  as before. A raw literal that goes on past its line is read as it was.
- IntelliJ's Stream Inspector looks the same at every zoom of the IDE. The text of an event, the
  signal store, the variables and the fields grew by the zoom twice, three times their size at
  175 %, and an event's name and a comment line kept the size they had when they came; all now
  follow the zoom, after it too. The fields have room around their text, the signal store keeps
  room for six lines, and the variables break between words. In a narrow panel nothing is cut or
  drawn over: Save and Delete go under the request list, an event's time and the status to a line
  of their own, and the store and the variables under the events.
- VS Code's Stream Inspector fits a narrow panel and keeps the store in sight. Its fields went
  past the right edge, the status out of view, and the signal store sat under every event, so a
  long stream pushed it out of reach. Now the fields give way, Save and Delete go under the
  request list together, an event's time and the status go to a line of their own, and a URL in
  an error breaks rather than run past the edge. The events and the store each scroll, the store
  under the events with a share of the height where the panel is narrow, and in a short panel the
  page scrolls to them. A label stands on the line of its field's text.
- In VS Code, the route of the code lens that first opens the Stream Inspector is the one filled
  in. The page asked for the form once it was ready and was answered with the last request; a
  page loaded again later still gets the last request.
- The note on a response that is no stream, in IntelliJ, breaks between words, as VS Code shows it.
- IntelliJ shows text it does not control as text where the platform reads HTML: a saved
  request's name in the Delete dialog, a route's path in the tooltip of its gutter icon, and the
  requests file's path in the Save button's tooltip and under the field in the settings. IntelliJ's
  message alert, its default on every OS, and every tooltip read HTML, so a name in the shared
  `inspector.json`, a path in a cloned project or in the shared `.idea/streamlord.xml`, such as
  `<b>x</b>`, was set in bold.
- The Stream Inspector shows the name of a data line as text. A server that sent
  `data: <html><img src=...> v` had the name rendered as Swing HTML in IntelliJ, its image fetched.
- HTML in a Kotlin string is coloured where the analysis reads HTML. A literal of the block of
  `interpolate { }` that writes a tag or marks a value with `+` is HTML however it opens,
  `interpolate { "Hello <b>${+name}</b>" }` too, and so is the argument of `Trusted(...)` that
  writes a tag, by position or by its name `html`, a sum of literals too; both are read so for
  completion, hover and warnings. A literal
  of the block that writes neither, or stands in a call's arguments or a lambda handed to one, a
  log message, a Datastar expression or a comparison, is a string as before. An
  annotation's message, `@Deprecated("<b>not</b> markup")`, is no HTML.
- IntelliJ asks the Kotlin plugin which strings it injects a language into itself, a sum joined
  with `+` as a whole, by a mark on what they are or on the parameter they are handed to, and
  colours the other HTML strings: a `@Language("HTML")` on another function's parameter no longer
  turns colours off for the strings after it, and an argument of a call whose parameter has no
  mark, `listOf("<li>a</li>")` or `call.respondText("<h1>x</h1>")`, is coloured. A string the
  Kotlin plugin injects keeps its injection with the mark's `prefix` and `suffix`.
- VS Code colours a literal that opens with a doctype, or with white space or `\n` before its
  first tag, `Trusted(...)`'s argument that writes a tag, on its line or the next, and the
  literal that opens the block of `interpolate` on its line, after prefixes and by the qualified
  name too. It reads one line at a time, so a literal further into a block, after the first of a
  sum handed to `Trusted`, or handed to a parameter of the file's own function marked
  `@Language("HTML")`, is coloured only when it opens with a tag, and only an annotation's first
  argument on its line is kept out of HTML.
- IntelliJ reads a project that lies under a folder named `build`, `out`, `dist` or `target`,
  which it took for build output and left unread, so every signal in it was unknown. It now counts
  folders from the outermost content root that holds the file, and leaves out hidden folders, as
  VS Code does.
- IntelliJ judges by their type the values it judged by the text alone, where it looked for the
  values itself and missed them: the sum that leads a chain, `first + last + "<p>...</p>"`, which
  is a warning where it holds a string and was a hint; a value marked inside two pairs of
  parentheses, `${+((count))}`; and a value in the expression of `attributes["data-text"] = "..."`
  or of an option such as `payloadExpr`.
- IntelliJ no longer takes a `List<Trusted?>` for markup, joined or as it is: a `Trusted?` writes
  `null`, as the analysis already read it from the text.
- IntelliJ takes a collection for one of markup by the list of collections the analysis holds:
  Kotlin's and Java's own, an `ArrayDeque` among them. `rows.joinToString("")` over a class of
  another library or of your own, `PersistentList<Html>`, is judged by the `String` it gives, and
  is a warning where it said nothing.
- IntelliJ reads how a value is built as the analysis reads it for VS Code, where it had rules of
  its own, and answers what only resolving tells: the type of a value and of its parts, whether
  each is a constant, and the declaration it names. A sum beside an HTML literal, `"<b>" + q`, and
  a function that returns one are markup, with the warning on `q` where it is written. An entry of
  an enum is the author's own whether or not the enum overrides `toString`. A `val` set to an
  HTML literal is markup with a declared type too, `val row: String = "<li>a</li>"`. A `val` or a
  function of another file is read with that file's imports, and what it calls in turn is not
  followed: `fun outer() = badge()` in another file is the `String` its type says.
- IntelliJ colours the value of `attributes["data-bind"] = "name"` as the signal's name it is, and
  no longer spell checks a signal's name in a DSL call, `dataBind("usrnm")`, as it never did in an
  attribute.
- IntelliJ reads a filter, `data-persist="{include: /^a/}"`, as the object literal it is wherever
  it is written: coloured in `attributes[...]` too, and only its strings spell checked.
- IntelliJ spell checks an expression written under the other bundle's prefix as an expression,
  only its strings, as it already coloured it: `data-star-text` in a project whose prefix is
  `data-`, in HTML and in `attributes[...]`.
- VS Code leaves a file on disk over 1 MB out of what it reads of the workspace, as IntelliJ does.
  An open document is read whatever its size.
- VS Code names a file's template engine by the file's name where its language names none, as
  IntelliJ does: a `.ftl` or `.jte` file that VS Code calls HTML gets its engine's rules, where its
  placeholders were judged as JavaScript.
- IntelliJ shows `one-value`, as VS Code does: the comma that `postExpr`, `peek` and the other
  helpers that write an expression as one value turn into the comma operator. It is under
  "Datastar expression".
- Both editors offer "Open in Stream Inspector" on a Ktor route under a comment that ends in a
  full stop, `// Opens the stream.` above `get("/counter") { }`, where they took the full stop for
  the dot of a call on a receiver, `client.get(...)`, and left the route out.

## [0.23.0] - 2026-11-16

This release lets a Kotlin value be written into an HTML string where it stands. Mark it with `+`
in the block of `interpolate`, and it is escaped or refused by where it landed, with no placeholder
and no argument to keep in order. Both editors read the block, and their fix for a value in an HTML
string now leaves the string as written and marks its values. A build can be held to it with
`failures(strict = true)`. The editors also judge more of an expression as the client reads it:
values in parentheses, functions called at once, and `evt.detail.type` however it is compared.
Nothing breaks: the placeholder form of `interpolate` and `failures()` are as they were.

### SDK

- streamlord-core: `interpolate` takes a block, with each value written where it stands and marked
  with `+`: `interpolate { """<li><a href="${+url}">${+title}</a></li>""" }`. A marked value is
  escaped or refused by where it landed, as the value of a `%s` is. A loop may stand in the block,
  a trim call runs before the values go in, and a value without the mark is written by Kotlin as in
  any string. The placeholder form stays, and is the one Java has.
  See [Strings](https://streamlord.sobernetics.no/strings/#values-from-outside).
- streamlord-core: `interpolate(prefixes) { }` takes the prefixes of an aliased bundle. The block's
  receiver is an `InterpolationScope` and a mark a `Placed`.
- streamlord-core: a mark of another block, or one in a string cut or changed after its values were
  marked, is no mark: no value stands there. The two characters a mark is made of, U+FDD0 and
  U+FDD1, are written as U+FFFD wherever a block's string or an escaped value holds them, so text
  another escaper wrote into the block never stops the page.
- streamlord-core: the refusal of a URL that loads code names "the value" where it named a
  placeholder.

### The analysis

- `failures(strict = true)` also keeps the warnings of a value written without its mark: one left
  without `+` in the block of `interpolate`, a template in its format, a `String` in an HTML string
  that is not `interpolate`'s, and a bare dollar in a `data-*` expression of one. `failures()` is as
  it was, and `ProjectIssue.failsStrict` says it of one issue.
  See [The analysis](https://streamlord.sobernetics.no/analysis/#a-test-over-your-own-sources).

### Editors

**Kotlin values in HTML**
- Both editors read the block of `interpolate`, written after the call or in its parentheses: a
  marked value is an error where `interpolate` will throw, and a value left unmarked gets "Mark …
  with +" as its fix.
- The rewrite of a string is now "Mark the values with + in interpolate { }", where it was "Build
  this string with interpolate(...)": the string stays as written, in the block, with each value
  marked where it stands. A margin or a constant with a per cent sign no longer stands in its way.
- The rewrite is not offered for a string that writes `this` or calls `toString()`, `let { }` and
  their like without a receiver, which in the block are the block's.
- The fix that marks values with `+` is the first one offered on a value, in a string and in a
  block.
  In IntelliJ it stands above "Mark … as markup", which the IDE listed first by its title.
- A value looked up in a name declared a map, `${m["key"]}`, may be null: the rewrite is offered
  and not preferred there, and the message says that `interpolate` writes nothing for null.
- The messages speak of marking: "mark it with + in interpolate { }" where they said "build the
  string with interpolate(...)", and "Marking the values is not offered here" where no rewrite is.

**Actions and options**
- Both editors no longer call a function called at once a function: `@get(function(){ return '/x' }())`
  and `{retry: function(){ return 'never' }()}` give what the function returns, and are not judged.
  Nor is what is read or called of a function, `function(){}.name` and `function(){}.bind(null)`.
- Both editors judge a value in parentheses or after a spaced sign as the value it is, and warn of
  `@get((1))`, `@get(- 1)` and `{retry: ('nope')}` as of the same without them. The warning marks the
  value inside the parentheses.

**Expressions**
- Both editors judge a value of `data-computed`, and the value of a signal filter attribute, in
  parentheses as what it holds, and warn of `(1)` and `{total: (1)}`.
- A value of `data-computed` that does not parse yet, `1 +` or an open string, gets its syntax error
  alone. That it is no function, that it changes a signal or calls an action, and that a
  `data-init` sends before its `data-indicator`, is said once the value parses.
- A value of `data-computed` that is complete where the rest is not, `{total: 1 // c, ...}` and
  `1 // c`, is now also said to be no function, beside the syntax error.
- Both editors compare `evt.detail.type` with a string however the two are written: in parentheses,
  after a comment or read with `?.`, `case /* never */ ('nope')`, `switch ((evt.detail.type))` and
  `evt?.detail?.type === 'nope'`, the type is judged, where it was passed over. A string joined to
  something or chosen by a condition, `case 'a' + 'b'`, is no longer judged by its first part.
- `await /* why */ @post(...)` is an error on `await`, with why, as `await @post(...)` is. After a
  line break `await` is a name that ends its statement, and the error is the one of the next line.
- A filter pattern written as a string with flags is warned of after a comment or in parentheses too,
  `include /* all */ : '/a/i'`. In a filter attribute only the filter's own `include` and `exclude`
  are judged, not those of an object deeper in the value.
- While an expression is typed, these three checks now speak of what is written to its end and
  before the first syntax error, and no longer of what stands after it: a case is judged at its
  colon and the `filterSignals` of an action once its braces are closed, where both were judged a
  little earlier, and a syntax error early in a handler silences the type and pattern warnings after
  it until the handler parses again.

Side note: the checks of a value now read the tree the JavaScript parser builds of it, in place of
reading the text again each on its own.

## [0.22.0] - 2026-11-15

This release brings Datastar 1.0.5 support to both editors. They check the attributes, the Pro
attributes and actions, every action's arguments and options, the casing of names and events, the
aliased prefix and the expressions as the 1.0.5 client compiles them, with fixes and links to the
reference. They flag what core refuses in an SSE call or an answer, in core's own words, and the
DSL now takes an expression wherever the client does. The hints and fixes for a Kotlin value in an
HTML string now say what `interpolate` really does. Highlighting covers all three markup styles:
the kotlinx.html DSL, HTML in Kotlin strings, and HTML files and templates. The default CSP policy
now requires Trusted Types. Several signatures changed: read Breaking changes first.

### Breaking changes

- streamlord-core: `interpolate(format, values: List<Any?>, prefixes)` is gone, and a `List`, `Set`
  or array handed to `interpolate` is one value, written in brackets and escaped, where a lone
  `List` was taken for the values. Spread it to pass its elements,
  `interpolate(format, *values.toTypedArray())`, and put an aliased bundle's prefixes first,
  `interpolate(prefixes, format, vararg values)`.
- streamlord-core: `interpolate` throws for a value in `data-match-media`, where it escaped one,
  since Datastar Pro may run the query. Write the query into the format, or use `dataMatchMedia`.
- streamlord-core: `PatchElements` and `ElementsResponse` throw `DatastarEventValidationException`
  for an HTML element in the `svg` or `mathml` namespace, and for an SVG or MathML element in `html`
  outside its root. Put HTML in a `<foreignObject>`, or send shapes with
  `namespace = ElementNamespace.SVG` or inside an `<svg>`.
  See [SVG and MathML](https://streamlord.sobernetics.no/protocol/#svg-and-mathml).
- streamlord-core: with `guardElements` on, a patch without a selector in `outer` or `replace` mode
  whose top-level element has no id throws `DatastarEventValidationException` (the client skips
  it), and a `data-*` name with a key or a modifier and two letters swapped, `data-no:click`, throws
  `MistypedAttributeException`.
- streamlord-core: `CspNonce.starterPolicy`, the default of `CspNoncePlugin` (streamlord-ktor) and
  `CspNonceFilter` (streamlord-spring), requires Trusted Types and allows only Datastar's policy,
  `CspNonce.TRUSTED_TYPES_POLICY`. Other code that writes into the DOM as markup or a script is
  refused, and so is a patch with an inline `on*` attribute or a `<script src>`. A page that needs
  either, or a library that creates a policy of its own, sets its own policy.
  See [the policy is yours](https://streamlord.sobernetics.no/security/#the-policy-is-yours).
- `ElementsResponse`, `respondElements` (Ktor), `datastarElements` (Spring), `elementsResponse`
  (streamlord-html), `PatchElements.remove` and `removeElements` take `viewTransitionSelector` after
  `useViewTransition`. Name the arguments that follow it (`eventId` and `retry` of a removal, the
  `streamlord` bean of `datastarElements`), and rebuild code compiled against the old signatures.
- streamlord-html: `FetchOptions.responseOverrides` and `ResponseOverrides` are gone, since the
  client never read the option, and the editors no longer offer it. Set the selector, the mode, the
  namespace and the view transition on `respondElements` or `datastarElements`, and `onlyIfMissing`
  on `respondSignals` or `datastarSignals`.
- streamlord-html: `SignalFilter` has three more values after `include` and `exclude`, so code
  compiled against its constructor or `copy` needs a rebuild. Source that names `include` and
  `exclude`, or passes them in that order, stays as it is.
- streamlord-html-pro: `fit` names its flags `shouldClamp` and `shouldRound`, as the reference does,
  where they were `clamp` and `round`.
- streamlord-spring: `datastarSignals(value, onlyIfMissing, streamlord)` takes the bean last, as
  `datastarElements` does. A call that passed the bean second by position no longer compiles.
- streamlord-test: `assertPatchElements` and `assertNoPatchElements` also match `namespace`,
  `useViewTransition`, `viewTransitionSelector`, `eventId` and `retry`, after `containing`, and
  `awaitPatchElements` takes them before `timeout`: name it, `timeout = 5.seconds`.
- streamlord-core: `JsonObject.decimal`, `JsonNumber.toBigDecimal` and `toBigDecimalOrNull` are
  extension functions. Import them in Kotlin; from Java call `JsonDecimals.decimal(object, key)`.
- streamlord-core: `CatalogNames.attributes`, the other attribute sets of core's catalog and
  `CatalogNames.VERSION` are internal. The version is `DatastarProtocol.VERSION`.
- streamlord-core: `landingAsParsed` (internal API) returns a `ValueLanding`, which has `nameStart`,
  as `valueLandings` lists them. Code compiled against it needs a rebuild.
- IntelliJ: the plugin runs only with the Kotlin plugin in K2 mode, the default. An IntelliJ 2026.1
  with K1 turned on no longer loads it.
- streamlord-analysis: every entry takes a `Source`, and `AnalyzeOptions` is gone. Write
  `analyzer.analyzeKotlin(Source(text, SourceLanguage.KOTLIN, prefix, constants = names))`, and the
  same for `analyzeHtml` and `analyzeKotlinSites`; `analyzeKotlin(text)` and `analyzeHtml(text)`
  stay. `analyzeProject` takes the prefix in place of options.
- streamlord-analysis: `AnalyzeOptions.foreignCalls` is `Source.foreign`, a function of a call's name
  and where it starts. `AnalyzeOptions.checkHtmlAttributes` is gone, with no replacement.
- streamlord-analysis: `Source.modules` replaces `AnalyzeOptions.core` and `htmlDsl`:
  `Modules.known(core, htmlDsl, coreHtml)`, `Modules.UNKNOWN` (the default, what null meant) or
  `Modules.FROM_IMPORTS`. Beside `analyzer.htmlStringAt(text, offset)`, `htmlStringAt(source,
  offset)` and `readsDsl(source)`, whether a file may call the DSL, are new.
- streamlord-analysis: `signalReferencesInKotlin` and `signalReferencesInHtml` are
  `signalReferences(source)`. `collectSignals`, `collectSignalDefinitions`, `signalDeclarations` and
  `collectSignalFacts` take a `Source` and read under `Source.prefix`, and leave out the DSL's short
  helpers for `Modules.known(true, false)`.
- streamlord-analysis: `constantNames` and `otherDeclaredNames` are one `declaredNames(text)`:
  `projectConstants(texts.map(::declaredNames))`. Both are in `KotlinDeclarationsKt`, where
  `projectConstants` was in `InterpolationsKt`, so code compiled against it needs a rebuild.
- streamlord-analysis: `unknownSignalIssue(ref, defined)` is gone. Call
  `projectSignalIssues(language, refs, project)`, which answers for every read of a file at once.
- streamlord-analysis: `analyzeProject` and `failures()` are declared beside `ProjectIssue`, on the
  JVM alone. Import `io.github.markusaugust.streamlord.analysis.analyzeProject` outside the package;
  from Java both are in `PlatformJvmKt`.
- streamlord-analysis: `ExpressionValidator.validate` takes `event`, the event a `data-on` listens
  for, and `prefix`, the prefix its messages name attributes with, after `attribute`. Rebuild.
- streamlord-analysis: only what the editors call is public. In place of what is now internal:
  - `sourcePosition`, `SourcePosition` and `Issue.position`: call `Issue.format(file, source)`.
  - `Requests.parseEnv`, `mergeVariables`, `substitute`, `Requests.Substituted` and `Env`:
    `Requests.environment` and
    `Requests.resolveRequest`. `sendableUrl`, `SendableUrl`, `requestUrl` and `ENV_KEYS`:
    `Requests.streamRequest`. `requestKey` and `serializeRequestsFile`: `saveRequest` and
    `removeRequest`.
  - `collectDeclaredSignals`: `collectSignalFacts(source).declared`. `Catalog.attributesByName`:
    `Catalog.parseAttributeName`. `lexKotlin`: `analyzer.lex`. `findKotlinStrings`:
    `analyzer.lex(text).strings`. `validateExpression`: `analyzeHtml`.
  - `expressionAtCaret`, `actionCallAt`, `signalPrefixAt`, `actionPrefixAt`, `valueAtCaret`,
    `attributeDoc`, `actionDoc`, `fetchOptionDoc`, `modifierForm`, `casingNote` and their result
    types: `complete`, `hover` and `signatureHelp`. `tagBeforeCaret` is gone.
  - With no replacement: `Catalog`'s other tables (`actions`, `actionsByName`, `ActionSpec`,
    `attributesByKotlin`, `domEvents`, `DomEvent`, `fetchEvents`, `fetchOptions`, `backendActions`,
    `compiledParameters`, `patchModes`, `scopeNames`, `namingHelpers`, `signalKeyAttributes`,
    `allCallSiteNames`, `htmlCallSites`, `scriptCallSites`, `selectorCallSites`), and `distance`,
    `Docs`, `JsParser`, `JsSyntaxError`, `KeyKind`, `keyKind`, `keyReading`, `rawKeyNote`,
    `wireKey`, `TEMPLATE_SYNTAX`, `hasTemplateSyntax`, `KotlinLex.stringAt`, `isStringStart`,
    `readKotlinStringAt`, `isKnownSignal`, `nearestSignal`, `isMarkupCollection`,
    `ValueFact.ofTypeName`, `Analyzer.callSiteNames`, `dslNames` and `Requests.lineAndColumn`.
- streamlord-analysis: `TokenKind`, `NamePart` and `ValueFact` have more values, so a `when` over
  them needs the new branches. `tokenizeExpression` takes the ranges a template owns.
- streamlord-analysis: in the catalog, core's SSE calls and answers are one list,
  `kotlinCallSites.events`, in place of `html`, `script`, `selector` and core's entries of
  `signals`. `CallSiteSpec` has no `selectorArg` and `modeArg`.
- streamlord-analysis: code compiled against the old constructors of `CallSiteSpec` (now with
  `more` and `oneValue`, also `copy`), `AttributeSpec` (`reference`), `Modifier` (`doc`, `flagDocs`,
  `suggest`), `SiteIssues` (`rewrite`), `KotlinString` (`literals`, `called`), `Interpolation`
  (`unread`), `SignalFacts` (`patchedTypes`, `imports`, `packageName`) and `MarkupOptions`
  (`prefixSetting`) needs a rebuild, and so does code that calls `MarkupValidator.validateAttributes`.

### Editors

- VS Code now runs IntelliJ's analysis, compiled from Kotlin to JavaScript, not a TypeScript copy.

**Attributes**
- Both editors: a bare `data-*` name one edit from a Datastar attribute, `data-persits`, gets a
  hint and a fix where its value or form says Datastar was meant. `data-test` says nothing.
- Both editors give `data-text-foo`, `data-show-more` and the like, a key on an attribute that takes
  none, the same hint where the value reads as Datastar's, and warn of `data-no:click`.
- Both editors: with the aliased prefix set, a plain `data-*` Datastar attribute with a key, a
  modifier or a Datastar value is a warning with a fix that writes the alias. Knockout's
  `data-bind="text: $data.name"` says nothing.
  See [the aliased bundle](https://streamlord.sobernetics.no/html-dsl/#the-aliased-bundle).
- Both editors read a page with the prefix you set throughout: `data-ds-signals:my-value__case.kebab`
  defines `$my-value`, and completion and messages use it. VS Code takes a blank prefix as `data-`.
- Both editors leave `data-star-rating` and other `data-star-*` names that are no Datastar attribute
  alone under the default prefix.
- Both editors leave markup under `data-ignore` (`data-star-ignore` under an alias), or on an
  element with `data-ignore__self`, unchecked, and its signals count as none. So does `dataIgnore()`.
- Both editors say that `data-ignore`, or an aliased bundle, keeps Datastar off a value another
  library reads, `data-bind="text: name"` or a share button's `data-text="Check this out"`.
- Both editors give `data-on:keyDown` and `Data-On:Click` a hint to write the event in lower case,
  where the fix wrote `key-down__case.camel`. `dataOn("keyDown", ...)` is a warning.
- Both editors keep the case of the prefixed events: `data-on:webkitAnimationEnd` gets the fix
  `webkit-animation-end__case.camel`, and `data-on:webkitanimationend` is a warning.
- IntelliJ no longer calls `DATA-TEXT` or `Data-On:Click` an attribute that is not allowed here, and
  shows a weaker problem inside an error, with its fixes, as VS Code does.
- Both editors warn of `data-indicator` after a `data-init` that sends a request on the same
  element, in markup or the DSL, with a fix that moves it first. An init with a modifier is fine.
- Both editors warn of `data-computed` that changes a signal or calls an action other than `@peek`
  and the Pro formatters. A function it holds and never calls is left alone.
- Both editors hold `data-json-signals` and `data-on-signal-patch-filter` to `include` and `exclude`
  patterns, as `filterSignals` is held.
- Both editors warn of `data-on-signal-patch-filter` without `data-on-signal-patch` on the element,
  and of `data-attr:view-box` on an SVG element, with a fix to the object form.
- Both editors read the value of `data-match-media` as a media query, with or without parentheses
  and quotes, and no longer as an expression with a syntax error.
- Both editors call a value of only spaces, `data-on:click="  "`, or an empty one an empty
  expression in HTML and Kotlin strings, as in the DSL. In the DSL `dataShow("")` is an error.
- Both editors link an issue on `data-nonce`, and VS Code's HTML data for it, to the security page
  of the Datastar reference.
- Both editors: the pre-1.0 fix needs a key after the dash, so a half typed `data-on-` stays.

**Modifiers**
- Both editors judge modifier arguments as the client reads them: `__case.snake.pascal` is no error,
  `__debounce` with no duration waits 0 ms (a warning), `__threshold.0.5` reads 0 (fix:
  `__threshold.50`), and a modifier written twice is read from the last.
- Both editors say what the client ignores or does with `__document` and `__window`, `__window` and
  `__outside`, and `data-on-interval` with no duration. Unlisted Pro modifiers are not judged.
- Both editors read the modifier lambda of a DSL call for what the DSL refuses as it renders: an
  unset duration flag, `half`, `full` and `threshold` together, a threshold outside 0 to 100, and an
  interval of no time.
- Both editors accept the modifiers of `data-on-raf`, `data-on-resize` and `data-animate` the
  reference does not list, but no longer offer or document them.

**Actions and options**
- Both editors check every action's arguments: too few, too many, or a literal of the wrong kind is
  a warning, and a signal filter is held to `include` and `exclude`.
- Both editors judge the Pro actions `@clipboard`, `@fit` and `@intl` by the kinds the reference
  declares, where they counted arguments alone. A number where a string goes is let be.
- Both editors judge a backend action's options as the client reads them: `contentTyp` gets the near
  name as a fix, and a literal of the wrong kind or value, `retry: 'sometimes'`, is a warning.
  Only a value written as a literal is judged.
- Both editors say what Datastar does with an option of the wrong kind (`retryMaxCount: true` counts
  as 1) and that a `contentType` it cannot read fails with FetchFailed. `@get(['/a'])` is no error.
- Both editors offer the types of `@intl` as its first argument and warn of one the reference does
  not name, with a fix for one in another case. `@gett` is offered `@get`.
- Both editors offer and check the types of `datastar-fetch` after `evt.detail.type ===` and in
  `switch` cases, so `'start'` for `'started'` is a warning with the fix.
- Both editors: a Kotlin template or a `%s` in an action call no longer turns off the checks of the
  rest, and `@${name}('/x')` is no longer an unknown action.
- Both editors check, complete, hover and colour the expressions of the DSL's `...Expr` helpers and
  options, also one handed to another call, and warn of a top-level comma in one, as JavaScript
  keeps only what follows it.
- Both editors, the DSL and the operations page document `retry`, `retryInterval` and
  `retryMaxCount` as 1.0.5 runs them.
  See [reconnects](https://streamlord.sobernetics.no/operations/#when-datastar-reconnects-and-when-it-does-not).

**Expressions**
- Both editors judge an expression as Datastar 1.0.5 compiles it: a template literal's text is no
  longer said to be rewritten unless its `${...}` holds a brace, and `return ...` is not split at `;`.
- Both editors no longer call an action after a template literal, `` `${$a}` + @peek(() => $b) ``,
  a syntax error, and say `Unexpected end of input` for a value that stops short, `data-text="1 +"`.
- VS Code says `Unexpected end of input` for an expression that stops short, `$count +` or
  `@post('/x'`, as IntelliJ does, where it said `Unexpected token`.
- Both editors warn of `evt.type` outside `data-on`, which throws, and of `$$count`, with a fix.
  `await @post(...)` is an error on `await`, with why.
- Both editors read `@get(+-1)` as a number, `@get(+1n)` as a TypeError, `${a, b}` in a template
  literal as no comma of `postExpr`'s value, and `/*c*/ 1` in `data-computed` as a value.
- Both editors read in time that grows with the length, where it grew with its square: a long
  datastar-fetch handler, many signal filters, and in VS Code a line of many attributes.
- IntelliJ: completion no longer slows down on a Kotlin file of thousands of DSL calls.
- Both editors: `dataAttr(name = "tab-index".uppercase(), expression = "0")` no longer stops the
  analysis of the whole file.

**Signals**
- Both editors warn on a signal the server reads, through `Signals` or a `@Serializable` class, that
  no page declares: `signal-never-declared`.
  See [the analysis](https://streamlord.sobernetics.no/analysis/#signals-the-pages-never-declare).
- IntelliJ: the inspection "Signal no file defines" is "Unknown signals", as the VS Code setting
  `streamlord.diagnostics.unknownSignals` is. Both turn this warning and the unknown-signal warning
  on and off. The inspection's settings in a profile are kept.
- Both editors take the keys of an object a signal is given as signals, `hero.strength` from
  `data-signals:hero="{strength: 1}"`, and rename an object signal without writing its keys over.
- Both editors: `$tick++`, `--$n` and `$total += 1` define their signal; a plain read does not.
  A Kotlin template before an `=` in a string, `"<p $name=\"x\">"`, defines none.
- Both editors read the keys of `PatchSignals` and `SignalsResponse` as the signals they declare.
- Both editors offer after `$` the signals markup or code defines, not every name read somewhere,
  and no name a DSL or core call writes with a hyphen. `data-signals='{"hs-key": 1}'` still is.
- Both editors no longer suggest such a name for an unknown signal: `$isOpen` beside
  `dataSignals("is-open" to false)` no longer says "Did you mean $is-open?".
- Both editors offer a `$name` a `$$"..."` literal leaves as text, keep a `@Serializable` class's
  signals while it is typed, and suggest the first by name of two equally near signals.
- VS Code finds the signals of `.tmpl`, `.gotmpl` and `.gohtml` files, as IntelliJ does, and of a
  file outside the workspace folders when it is opened, so navigation and rename work from it.

**SSE calls and answers**
- Both editors flag a literal argument of an SSE call or an answer that core refuses, in core's
  words: `patchElements throws: Patch mode 'inner' requires a selector.`
- That covers an empty or non-ASCII `eventId`, a `retry` of no time, less or a fraction of a
  millisecond, a blank or multi-line selector or `viewTransitionSelector`, signals that are not one
  JSON object, a blank script,
  a `javascript:` redirect, elements of only white space, and a selector no header can carry.
- It also covers a script attribute HTML cannot name, the effect attribute beside `autoRemove`, a
  line break in an attribute of `respondScript`, a blank selector of `PatchElements.remove`, a
  signal name core cannot remove, and an element in the wrong namespace.
- Both editors offer the fix `removeSignals` has for a hyphen in `PatchSignals.remove`, and name a
  companion's function as it is called: `ExecuteScript.redirect throws: ...`.
- Both editors judge a call in a file that imports Streamlord, on no receiver, `this`, core's
  companions, Ktor's `call`, or a stream: a receiver declared as one under any name, or, with no
  type in sight, one named `sse`, `stream` or `datastar`. `doc: Document` is not one.
- Both editors no longer warn that Streamlord escapes `</script` in `respondScript`,
  `ScriptResponse` or `datastarScript`: an answer sends its script as written.

**Kotlin values in HTML**
- Both editors link a warning about a Kotlin value in HTML to the page on HTML strings, and advise
  an attribute of its own read with `el.dataset` where they advised a signal.
  See [Strings](https://streamlord.sobernetics.no/strings/).
- Both editors title the rewrite "Build this string with interpolate(...)", the same wherever it is
  offered, naming a value it passes as `Trusted(...)`, a URL it refuses, or the inner string.
- Both editors offer the rewrite as a context action anywhere in a string with a value to escape: an
  intention in IntelliJ, a refactoring in VS Code.
- Both editors offer the rewrite for a literal with `.trimIndent()` or `.trimMargin()`, a `$$"..."`
  literal, and literals joined with `+`, and keep each form. A `trimMargin` string is read as HTML.
- Both editors do not put the rewrite first under `.trimIndent()` or for a value that may be null,
  which `interpolate` writes as nothing where a template writes `null`, and the message says so.
- Both editors offer no rewrite where it would change the page: a call after a literal of a `+`
  chain, a string in an annotation or a `const val`, a `List`, `Set`, `Map` or array value.
- Both editors call a value after `javascript:` or `data:text/html,` a URL that runs whatever it
  holds, offer no rewrite there, and make such a placeholder in `interpolate` an error.
- Both editors no longer offer the rewrite for a `Trusted` in an attribute or a URL, and let a
  `Trusted` in `interpolate` land anywhere. IntelliJ knows one by its type, `user.badge` too.
- Both editors: when a value keeps the rewrite from the string, each other value's message names it
  and where it stands. A constant, an enum entry or `${"$"}` stays a template and blocks nothing.
- Both editors pass CSS of yours in a `<style>` as `Trusted("$v")` and a String as `Trusted(v)`,
  and say a String there is read as CSS.
- Both editors: a number, a boolean or a nullable one in a script, a style or a handler, and a
  boolean in `style`, is no warning. A `kotlin.Number` still is.
- Both editors give a value shaped as a number, `${xs.size}` or `${a * 2}`, the rewrite in place of
  "Mark … as markup". VS Code reads comparisons, arithmetic and an enum's `name` as IntelliJ does.
- Both editors say nothing of the `html` of a `Trusted`, a `List<Html>`, or a value that opens a `+`
  chain, `w + "<p>...</p>"`, which are markup you built.
- Both editors put "Quote the value" first only for a number or a boolean, and otherwise say the
  quoted value is still to be escaped.
- Both editors judge a string marked by the qualified `@Language("HTML")` or passed to an annotated
  parameter in the same file, and a literal inside a `+` chain or one of its templates.
- Both editors judge a value joined before the first literal, after a comment or to a literal with a
  call after it, a name in backticks, and the URL of a `<meta>` refresh as a URL.
- Both editors trim only the literal a trim call follows in a `+` chain, as Kotlin binds it, and
  mark a value they cannot read to its end only where it certainly is.
- Both editors check a call to `interpolate` with a trim call after its format, a format passed by
  name, or prefixes, `interpolate(listOf("data-x-"), format, ...)`.
- Both editors no longer put the `%s` placeholder fix first under `.trimIndent()` or for a
  `String?`, and its title says how `interpolate` writes a `Trusted` and an `Html`.
- Both editors count a list handed to `interpolate` as one value, with a hint and a fix that writes
  out the elements of a `listOf(...)`, `setOf(...)` or `arrayOf(...)`.
- Both editors warn of a value in `data-match-media` and no longer offer the rewrite for it.
- Both editors show a value of a `$$"..."` literal in a `+` chain as `$${name}`, and "Mark … as
  markup" writes both dollars. In a raw literal it is quoted without a backslash.
- Both editors read a `val` set to `interpolate(...)`, or its `.text`, however many blanks precede it.

**Completion and hover**
- IntelliJ lists the events after `data-on:` by their names, `click`, as VS Code does. In both, an
  attribute that needs a key is written `data-on:key=""`, with `key` selected.
- Both editors complete option values after `retry:`, `contentType:` and `requestCancellation:`,
  `true` and `false` for a boolean, and `include` and `exclude` in a filter, in the free quote.
- Both editors complete the values after `__threshold.`, `__prop.` and `__event.`, a duration flag
  once, and the defined signals in `data-bind`, `data-indicator` and `data-ref`.
- Both editors leave out the keys an options object has, and offer the members of the event after
  `evt.` in `data-on`.
- Both editors complete the key of `attributes["data-..."]`, and VS Code hovers it. After
  `<div a= data-` or a no-break space no attribute names are offered.
- Both editors replace the rest of the word with a completion: in VS Code as its insert mode
  setting says, in IntelliJ with Tab.
- Both editors' hover on an action names each parameter as the reference has it and links its place
  in the reference. Signature help says what each parameter is.
- Both editors' hover on a modifier shows its own documentation and tags. On `el`, `evt`, `patch`
  and a signal it says what each is, a signal as the path the client reads.
- Both editors document filter keys, option values, the `datastar-fetch` types and the Datastar
  events, and VS Code the DSL's `...Expr` helpers.
- Both editors show hover on the first character of a word, and on a signal written with `${'$'}`.
- IntelliJ no longer lists `datastar-fetch` twice, offers the file's words after `__`, or offers to
  import `CatalogNames.attributes`. VS Code no longer shows two hovers on `data-text`.
- VS Code shows an option's default after its name, as IntelliJ does.

**Highlighting**
- Both editors colour every part of an expression and attribute name with keys the theme colours:
  keys, properties, calls, globals, operators, comments, keywords, numbers and template literals.
- Both editors colour a quoted key, `{'text-red-500': $err}`, and its colon as a key, a slash after
  a value as a division, and `&quot;x&quot;` as a string.
- Both editors colour the value of `data-bind`, `data-indicator` and `data-ref`, and the name
  `dataBind` and `dataSignals("name", ...)` take, as a signal, and `{include: /x/}` as an object.
- Both editors colour `$name` in an ordinary Kotlin string and `$${id}` in a `$$` string as Kotlin's
  templates; `\$name`, `${'$'}name` and `${'$'}{${'$'}b}` still read as Datastar's.
- VS Code gives `{{ user.name }}`, `{% if %}` and `${...}` in a value in an HTML file, and
  Thymeleaf's `th:attr="data-signals=${x}"`, no Datastar colour: they are the engine's.
- Both editors leave `${...}` in a value of a jte, kte, FreeMarker or Velocity file, also inside a
  JavaScript template, FreeMarker's `<#if>` and `<@macro>` and PHP's `<?= ?>` to the engine, with no
  expression error. In Razor every `@` is Razor's, and `@@post(...)` reads as `@post(...)`.
  See [templates](https://streamlord.sobernetics.no/templates/).
- IntelliJ's colours fall back to standard keys of your scheme: an attribute name takes the HTML
  attribute colour, an action the function declaration's, a modifier argument the constant's.
- IntelliJ colours actions in the classic Default scheme and globals in Darcula and the New UI's
  Dark, and its colour settings page shows every key.
- IntelliJ colours the `data-star-` prefix, a name in capitals, a value around a template
  placeholder, `$a` beside `{{#flag}}`, `.hbs` files, and JSON in escaped quotes.
- IntelliJ colours `interpolate`'s format however it opens, and no longer colours a name that is not
  Datastar's in a template.
- VS Code colours Datastar in Handlebars, jte, Mustache, FreeMarker, Velocity, Twig, PHP and Razor,
  on SVG and MathML elements, in an unquoted value and after a modifier with an argument.
- VS Code: the `streamlord.languages` default names FreeMarker `freemarker`, where it said `ftl`, so
  `.ftl` files get diagnostics, completion and hover.
- VS Code colours DSL expressions passed by name in any order, on the next line, as a lone object or
  after a receiver, and the JavaScript of `ScriptResponse("...")`.
- VS Code colours HTML under `@Language("HTML")`, also by its qualified name, in
  `interpolate(...)` and in a `trimMargin` string, however the string opens.
- VS Code reads `$$count` and `$${id}` in a `$$"""` string that opens on the next line as Kotlin's.
- VS Code colours an unclosed quote or `/*`, and `@post([[@{/save}]])` in Thymeleaf, as the value
  alone, `"<div class=\"btn\">"` and a value's quotes as a string, `evt?.target` as a property.
- VS Code: the recommended colours, still opt in, follow the theme's kind.

**Inspector, routes and files**
- IntelliJ's inspector folds a signal patch deeper than 64 levels into the store, as VS Code does.
  In both, one deeper than 1000 levels, or with a number of more than 1000 digits, leaves the store
  as it was and says why.
- Both editors judge a saved request's signals with the SDK's reader, so VS Code now refuses ones
  nested deeper than 64 or with a number longer than 1000 digits, as IntelliJ did, in its words.
- IntelliJ's inspector shows the first 10,000 characters of a line and says it is cut, where a line
  of a megabyte froze the IDE.
- Both editors: VS Code's Open in Stream Inspector lens and IntelliJ's route marker no longer stand
  on a DSL action handed to an attribute, and a Ktor `sse("/feed") { }` route gets one, also one
  whose arguments hold a lambda or a call.
- VS Code works in a `.kt` file with Streamlord alone, as it declares the Kotlin language itself.
  A file the analysis fails on gets a line in the output channel and no longer stops the others.

### SDK

- Streamlord speaks Datastar 1.0.5: `DatastarProtocol.VERSION` is `1.0.5`, the catalog is
  `catalog/datastar-1.0.5.json`, and the docs and the demo load 1.0.5. A 1.0.4 page works as before.
- streamlord-core: `ExecuteScript.redirect`, `replaceUrl`, `pushUrl` and `PatchSignals.remove` build
  the events for a `Flow` on WebFlux or `respondDatastar(flow)` on Ktor.
  See [Spring WebFlux](https://streamlord.sobernetics.no/spring-webflux/#redirects-the-address-bar-and-removing-signals).
- streamlord-core: an answer of elements that is no stream can name `viewTransitionSelector`, sent
  with `useViewTransition` as `DatastarProtocol.Headers.VIEW_TRANSITION_SELECTOR`.
  See [view transitions](https://streamlord.sobernetics.no/protocol/#view-transitions).
- streamlord-spring: WebFlux reads signals with `exchange.readSignals()`, `readSignals<T>()`,
  `readSignalsOr(default)` and `readSignalsJson()`, each `suspend`, with the limits and checks of
  the others, and has `WebFluxIncomingRequest` and `exchange.isDatastarRequest`.
  See [reading signals](https://streamlord.sobernetics.no/spring-webflux/#reading-signals).
- streamlord-spring takes reactor-core as `compileOnly`, which every WebFlux application has.
- streamlord-html: the backend actions take expressions: `getExpr`, `postExpr`, `putExpr`,
  `patchExpr`, `deleteExpr`, `queryExpr`, `headerExprs`, `requestCancellationExpr` and `setAllExpr`.
- streamlord-html: every option of a backend action has an `...Expr` twin, a `SignalFilter` holds
  expressions (`includeExpr`, `excludeExpr`, `filterExpr`), and `peekExpr` hands `@peek` a function.
  In streamlord-html-pro, `fitExpr` takes the ends of the ranges as expressions.
- streamlord-html: each expression is written in parentheses, so a comma or a brace in it never
  opens options of its own. `payloadExpr` and `clipboardExpr` refuse a blank one.
- streamlord-html refuses a header named twice, in any case, and `requestCancellationExpr` set with
  `requestCancellation`.
- streamlord-html: `peek` writes its expression in parentheses, so `peek("{a: \$a}")` returns the
  object, and `fit` and `intl` write their value as one argument.
- streamlord-html: `payload = null` is written as `payload: null`, so the client sends `null` where
  it sent the signals.
- core, Ktor, Spring WebMvc and WebFlux read a `null` body or `?datastar=null` as no signals, where
  they threw: `readSignals()` gives `{}`, `readSignalsJson()` and `readSignals<T>()` give `null`,
  and `readSignalsOr(default)` the default.
- streamlord-html: every modifier property of the DSL has KDoc.
- streamlord-core: `interpolate`'s message for a `String` in a `data-*` expression advises an
  attribute read with `el.dataset`, where it advised a signal.
- streamlord-core: `PatchSignals` and `SignalsResponse` still refuse JSON that is no object, now
  saying why: `the client patches its items as signals named 0, 1 and on`, where they said the
  client runs it as JavaScript. `SseDecoder` says the same of such a frame, and tells a selector
  over two data lines as the stream wrote it.
- streamlord-core: `SseDecoder`, so `datastarEvents` and `LiveDatastarStream` too, reads signals
  written as an object literal of plain values, `{foo: 1, bar: 2}`, where it threw. Computed ones,
  `{a: 1 + 1}`, still throw, and sending still takes JSON.
- streamlord-core: `SignalsCodecException` names a class by its simple name, `JSON cannot hold
  Sorcerer.`, where it gave the qualified name, and `null` for a local class.
- streamlord-core: `JsonParseException.limit` says the text is JSON past one of the parser's limits.
- The `415` that `readSignals` answers a form with says to read the fields as form parameters.
  See [a form](https://streamlord.sobernetics.no/signals/#a-form).
- streamlord-test: `datastarEvents(body, header)` reads an answer that is no stream, by its content
  type and headers.
  See [Testing](https://streamlord.sobernetics.no/testing/#an-answer-that-is-no-stream).
- Docs: [Spring WebFlux](https://streamlord.sobernetics.no/spring-webflux/#heartbeats) says a flow
  has no heartbeat, whatever the bean says, and shows the ticker that keeps it from being silent.
- Docs: [Security](https://streamlord.sobernetics.no/security/) says that a relative URL may lead
  to another host, that CSP mode does not make an expression safe and a page without it needs
  `'unsafe-eval'`, that the aliased bundle reads `data-star-nonce`, and that signals hold no secret.
- Docs: [Strings](https://streamlord.sobernetics.no/strings/) names every URL attribute
  `interpolate` holds to a safe scheme, `formaction` and `xlink:href` among them.

### Analysis API

- `complete`, `hover`, `signatureHelp` and `offers` are what both editors call for completion,
  hover and signature help, over a `Source` and, for completion, a `Workspace` (the signals, ids and
  classes defined elsewhere). An item's snippet is its text and the places to fill.
- `Source` holds the text, its language, the prefix, `modules`, `constants`, `valueFact`,
  `foreign`, `prefixSetting` (the setting the aliased attribute warning names) and `engine`.
- `Source.engine` is `TemplateEngine.JTE`, `FREEMARKER`, `VELOCITY` or `RAZOR`, and
  `templatePlaceholders` takes it second. What all engines share is `catalog/template-syntax.json`.
- `Modules.known(core, htmlDsl, coreHtml)` with `coreHtml` false, a core without `Html`, makes the
  advice for a value in an HTML string name `Trusted(...)` alone.
- New: `projectSignalIssues` and `ProjectSignals`, `signalNameStrings` and `SignalNameString`,
  `rewriteAt`, `tokenizeSignalName` and `templatePlaceholders`.
- New in `Requests`: `streamRequest`, `signalsProblem`, `environment`, and `patchedStore` with
  `PatchedStore`. `parseRequestsFile` takes null for a file that is not there.
- `ProjectFiles` holds the folders a project walk leaves out and the extensions read as markup.
- `analyzeKotlinSites` also lists an HTML string with no issue that can be rewritten,
  `SiteIssues.rewrite`. `KotlinString` has `literals` and `called`.
- `ValueFact.TRUSTED` is core's `Trusted`, judged as `MARKUP` is; `isMarkup()` holds for both.
- `signalDrift` counts a class a handler patches as declaring its properties, takes no read from a
  comment or a string, and judges a class by the reading file's imports and package.
- The catalog gives each action the kinds of its arguments, each option its kind and values, each
  modifier its documentation and suggestions, and an attribute its `reference` page.
- In the catalog, an expression call site may name more expression parameters, `more`, and say the
  DSL writes its expression as one value, `oneValue`; `expressionOptions` lists the options. A DSL
  expression may be passed by its parameter's name, and `dataAnimate`'s is its last argument.

## [0.21.0] - 2026-11-11

### Added

- streamlord-analysis has `tagBeforeCaret`: the markup of the tag a caret stands in, from its `<`,
  read as the browser reads it, for markup and for an HTML string in Kotlin.
- streamlord-analysis has `Requests.previewUrl`: a URL with its variables filled in as a request
  sends it, a param encoded and one that cannot be filled left as written.

### Changed

- `HoleLanding` in streamlord-core has a fifth value, `tagStart`: where the tag a hole landed in
  starts, or -1. It has a default, but code compiled against the four-value constructor needs a
  rebuild.
- `Requests.upsert` and `Requests.remove` in streamlord-analysis are gone, since they changed a
  requests file by writing it anew, which lost what the file held besides its requests.
  `Requests.saveRequest` and `Requests.removeRequest` take the text of the file and return it with
  the one entry changed, as a `RequestsEdit`: other members, entries that are not read and the
  layout stay as written, and an entry that is saved keeps its own other members and the object
  form of its `signals` and `headers`. Each touches the first entry of the name and no other.
- `RequestsFile` has a third value, `skipped`, with one message for each entry of the file that
  is not read, so code compiled against its two-value constructor or `copy` needs a rebuild.
- `Requests.parseRequestsFile` reports a file whose root is no object, whose `requests` is no
  list, or whose `version` is not 1, in `error`, where it read each as a file of requests; a
  save then wrote over them. A second entry of a name is not read, and is named in `skipped`.
  An empty file is a file without requests, and no longer invalid JSON, and `Requests.parseEnv`,
  `withEnvVariables` and `withBaseUrl` read an empty env file as a missing one.
- `Requests.requestsPathProblem` refuses a path that names `.streamlord/env.json`, however it
  is spelled, one with a backslash and one that ends in `/`.

### Fixed

- In both editors, an attribute on a line of its own, in a tag opened on a line above, gets its
  completions: the events after `data-on:`, the modifiers after `__` and their values, and in a
  Kotlin string the attribute names as well. Completion read only the caret's line, so it found
  no tag there and offered nothing. The tag is now read as the browser reads the markup, so the
  text of a `<script>`, `<style>`, `<textarea>` or `<title>`, a comment and a quoted value hold
  no tag, and in VS Code a `<` in the Kotlin code before an HTML string no longer makes the
  string read as inside a tag.
- In both editors, the inspector reads `.streamlord/inspector.json` as an open editor has it,
  saved or not, as it reads `env.json`, and the Request list follows it as you type. **Save** and
  **Delete** change the open editor's text as one edit that can be undone, where they wrote the
  file underneath it: IntelliJ asked which version to keep, and what was typed and not saved was
  lost. A requests file at a path the settings name outside `.streamlord/` is followed as well,
  however the path is written. In VS Code, an editor that does not take the change says so, where
  the save was reported done.
- In VS Code, a `method` in `inspector.json` that is not text is GET, as in IntelliJ, where
  `["post"]` was POST. A header value that is a number is read as written, `1.0` and `1e2`, where
  it was `1` and `100` and a number past 2^53 was rounded.
- In both editors, a header value in `inspector.json` that is an object or a list is read as it
  is written, less its white space, so the two editors send the same header. They spelt escapes
  their own ways, IntelliJ kept one of two keys of a name, and VS Code put a header named like a
  number, such as `10`, first.
- In both editors, the inspector's status says "error" only for an error that keeps the request
  from being sent or ends it, and its tooltip names that request's URL. A Save, Delete, Edit
  variables, Use baseUrl, Copy as curl or signal patch that failed while a stream was open set
  the status to "error", and in IntelliJ disabled **Stop**, though the stream went on; and a
  failed connection showed "closed", or "idle" in VS Code. A request that was stopped, or
  followed by another, no longer sets the status, or shows an error, when its close or its error
  comes late. In VS Code, a Stop or a second Connect while a request is still being prepared
  keeps it from being sent, where it was sent anyway.
- In both editors, the URL shown under the inspector's URL field is the one that is sent. It
  showed a param as written, `/feed/4 2/x`, where the request sends `/feed/4%202%2Fx`.
- In both editors, **Save** and **Delete** in the inspector stay where they are when "modified"
  appears beside them. They moved to the left, so a click where Save stood hit Delete.
- In IntelliJ, a long error in the inspector's list of events is read whole, on as many lines as
  it takes, where it was cut at the edge of the list, and so is the URL under the URL field.
  The fields of the form give way in a narrow tool window and the action buttons go on to a
  second line, where Save and Delete were laid out past the edge.
- In IntelliJ, the tooltip of the inspector's status shows the URL as written. The IDE renders a
  tooltip as HTML, so a URL with `&copy=1` in it showed a copyright sign, and markup in a URL
  from a shared requests file was rendered. The tooltip also has the status whole and is set
  anew with each status, where it kept the URL of an earlier request; a long status is cut in
  the panel when the action buttons need the room, so they keep it in a narrow tool window.
- In IntelliJ, the inspector's **Stop** button is disabled until a request is connecting or open.
  It was enabled in a panel that had not connected to anything.
- In both editors, **Save** and **Delete** in the Stream Inspector wrote
  `.streamlord/inspector.json` anew, which dropped what a person had written in it: an entry
  without a `url`, members of their own such as a `"note"`, and the object form of `signals` and
  `headers`. They now change the one entry and leave the rest of the file byte for byte, in the
  line endings the file has, and write nothing when a request is saved as it was read.
- In both editors, a requests file whose root is a list, or whose `requests` is no list, read as
  a file without requests, and the next save wrote over it. It is now said to be no requests
  file and left alone, and so is a file of another `version` than 1. An empty file is a file
  without requests, where it was invalid JSON and stopped a save.
- In both editors, the inspector says under the Request list what it does not read: an entry
  without a `name` or a `url`, and a second entry of a name, which a save of that name used to
  delete. It says there as well when the setting for the requests file names a path it refuses.
- In both editors, the setting for the requests file took `.streamlord/env.json`, and a save
  then wrote the requests over the variables. That path is refused, as are one with a backslash
  and one that ends in `/`; the IntelliJ settings page says so in the field and does not store
  the path.
- In IntelliJ, the inspector reads the requests file and the default base URL again when the
  settings are applied, where it showed the old ones until something else made it read.
- In VS Code, a requests file that could not be read was taken for a missing one, and a save
  wrote a new file over it; and two saves at once could each write over what the other had read.
- In IntelliJ, the plugin no longer asks the indexes or reads files on the UI thread where it
  did: the Stream Inspector reads `inspector.json` and `env.json` and sends its requests in the
  background, typing `(` or `,` asks in the background whether there is parameter info to show,
  and a signal rename finds its signal under a progress bar. Open Stream Inspector works while the
  IDE indexes, the signal index stops for a write that waits on it, and Re-index Signals can be
  cancelled from the status bar. Connect and Copy as curl read the variables and the default base
  URL again first, so a change in the settings counts at once.
- In IntelliJ, the documentation for Datastar attributes and actions goes through the platform's
  Documentation Target API. Where another plugin documents the same `data-*` attribute, both
  answers are shown, where that answer hid Streamlord's.

## [0.20.0] - 2026-11-09

### Security

- `interpolate` wrote an `Html` value as markup where it reshapes the markup around it in the
  browser, so that the format after it is read otherwise and a value written later could land where
  it was not judged:
  - in an `<svg>` or a `<math>`, a tag that takes the browser back to HTML closes the svg and the
    elements around the value: `<svg><g>%s</g></svg>` with `<em></em>`, a `<p>`, a `<br>` or a
    `<font color>`;
  - an element the value leaves open is closed only by an end tag that reaches it, and some never
    are: an `<object>`, an `<applet>` or a `<marquee>` bounds every one, the svg's end tags stop at a
    `<foreignObject>`, a `<desc>` or an `<mi>` with a `<g/>` left open in it, and an `<a>` is opened
    again around the text after its parent;
  - a table part out of its place closes the table or the cell around it: a `<td>` in a `<td>`, a
    `<td>` in an svg's `<desc>` in a table cell, or a `<table>` between rows; and a first `<col>` in
    a `<template>` or at the start of a patch drops all that follows it;
  - an end tag closes nothing past an element that bounds its search, a formatting element's leaves
    a block opened in it open, and a formatting element another end tag closes is opened again, so
    in `<div>%s</div>` the values `<div><object></div>`, `<b><div>x</b>` and
    `<noscript><b>x</noscript>` take the text after them into their own elements;
  - a start tag that closes the format's `<p>` closes what the value has open as well, so the
    value's own end tags then close the format's elements;
  - a `<form>` where the format has started one, even one whose parent has closed, moves the
    format's fields out of their form;
  - straight in a patch in the svg namespace, an `<image>` or an `<input>` is an svg element left
    open around the rest of the patch.

  Such a value is escaped now, and so is any `Html` that leaves an element open where it is written,
  such as an `<a>` without its `</a>`, or an `<option>a` in `<select>%s</select>`. `<path/>` in
  `<svg>%s</svg>`, `<p>x</p>` in a `<foreignObject>`, `<ul><li>a<li>b</ul>`, an `<input>` in a
  `<form>`, cells in `<tr>%s</tr>`, rows in a `<tbody>` or at a patch's top level, a whole table
  and a whole form are still markup. 0.19.0 has the fault: it writes `<em></em>` into
  `<svg><g>%s</g></svg>` as markup.
- streamlord-live, the service behind the documentation site, runs on a distroless Debian 13 image
  with Debian's `zlib1g` fixes for CVE-2026-76844 and CVE-2026-85091, both rated high.

### Changed

- A `%s` in `interpolate`'s format is called a placeholder everywhere Streamlord shows it: in the
  documentation, in the editors' messages and fixes, and in what `interpolate` throws. The editors'
  error for a `%s` count that does not match the values is `interpolate-placeholders`, where it was
  `interpolate-holes`; anything that names the old code needs the new one.
- `HoleFact` in streamlord-analysis is `ValueFact`, and `AnalyzeOptions.holeFact` is
  `AnalyzeOptions.valueFact`. Code that names either needs the new name.
- The `holeLimit` parameter of `mergePatchAsClient` in streamlord-core is `emptySlotLimit`, and its
  refusal speaks of empty slots. A call that names the argument needs the new name.
- `RAW_TEXT`, `HTML_SPACE` and `rawTextEnd` in streamlord-core (internal API) are internal, since
  only core reads markup with them now, and `commentEnd`, which nothing called, is gone. Code that
  opted in to them needs a copy of its own.
- `tokenize` in streamlord-analysis reads markup by core's walk, the one reading the caret has. It,
  `MarkupOptions` and `valueAtCaret` take a new `templates`, true by default, for a template or an
  HTML file, and false for an HTML string in Kotlin. Kotlin source that calls them compiles as
  before; Java source has to pass it, and code compiled against the old signatures needs a
  rebuild. Its own tree builder for svg and math is gone.
- `ValueFact` has a new entry, `OTHER`, for a class the editor resolved whose name the text alone
  would take for Streamlord's `Html` or `Trusted`. A `when` over every `ValueFact` needs a branch
  for it.

### Added

- streamlord-analysis has `ValueFact.ofTypeName`, `ValueFact.ofResolvedClass`, `isMarkupCollection`
  and `opensWithTag`: what a declared type, a class the editor resolved or the start of a string
  literal tells of a value joined into HTML. The analysis,
  its TS twin and the IntelliJ plugin read types and literals with them, where each had a copy of
  its own.
- streamlord-analysis has `writesItself` and `isMarkup` on a `ValueFact?`: whether the value is the
  author's own or cannot carry code, and whether it is markup. What a fact says of a value is
  asked in one place in the analysis and in its TS twin, and the IntelliJ plugin asks it there.

### Fixed

- `interpolate` escaped an `Html` value of an element that holds HTML, such as
  `<a href="/x"><span>a</span></a>`, a `<button>` or a `<label>` with a `<span>` in it, or a
  `<section>` with a `<p>`, written into a `<div>`, an `<li>` or another element. It read the value
  as if the format were an svg, where the `<span>` leaves the `<a>`. It reads the value where it
  lands now. 0.19.0 has the fault.
- A value declared `Html` or `Trusted` of another package was taken for Streamlord's markup:
  joined into HTML, handed to `interpolate(...)` or joined with `joinToString`, without a word. It
  is judged now as any value of a type the editor does not know, whether the type is written out in
  full, `com.acme.Html`, or the file takes the bare name from elsewhere: `import com.acme.Html`,
  `import com.acme.Markup as Html`, or an `Html` it declares itself. A star import of another
  package leaves the name Streamlord's, since nothing says the package has one. The same holds for
  a `Trusted(...)` of another package, which interpolate no longer lets into a `<script>` and whose
  literal is no longer read as markup in place. IntelliJ in K1 mode reads the type the declaration
  resolves to, as K2 does. IntelliJ also goes by the class it resolves where the text alone would
  say Streamlord's: an `Html` of the file's own package declared in another file, one a star import
  brings in from another package, or one written in backticks. That goes for a `Trusted(...)` too:
  its literal is not read in place, and `interpolate` does not let it into a `<script>`.
- Streamlord's own `Html` and `Trusted` imported under another name,
  `import io.github.markusaugust.streamlord.core.domain.Html as Page`, are markup: a value declared
  `Page` or `List<Page>`, a call to `T(x)` or `T("...")` for a `Trusted` imported as `T`, and a
  `val` set to one, asked for `interpolate(...)` they do not need. A name in backticks is the same
  name, for `Html` and `Trusted` and for their aliases, in the import, the type or the call:
  `` import …Html as `Page` ``, `` h: `Html` ``, `` `T`(x) ``. A class of the file's own written
  `` `Html` `` still takes the name from Streamlord's.
- A value declared a list of `Html` that may be null, `List<Html?>`, or `List<out Html>`, is markup
  when joined with `joinToString`, as an `Html?` on its own is. Both were asked for
  `interpolate(...)`.
- A comment after a value handed to `interpolate(...)`, `Trusted(x) /* id */`, made the analysis
  read the comment as part of the value, so a `Trusted` was not let into a `<script>`. The value is
  read without it, and IntelliJ's reading of its type now reaches it too.
- An `interpolate` imported from another package, `import com.acme.interpolate`, is no longer
  checked as Streamlord's: its format got errors about placeholders it may not have. Nor is one the
  file declares, since the text cannot tell which the call fits.
- A string literal that opens with a tag a template names, `"<$tag>x</$tag>"`, is markup in VS Code
  too, as it was in IntelliJ; VS Code asked for `interpolate(...)` around it. The template in the
  tag name is still judged on its own.
- IntelliJ reads a value whose type is Streamlord's `Trusted` as markup, as VS Code does, by the
  type it resolves: a class of your own named `Trusted` is not. It reads a literal past a `|`
  margin only when `.trimMargin()` is called on it.
- Literals joined with `+` whose text opens with a doctype, `"\n" + "<!doctype html>..."`, are
  checked as HTML, as one literal that opens with it is.
- A vertical tab before the first `<`, and in VS Code a no-break space, made a literal markup. Only
  the whitespace HTML has does now.
- In both editors, inside a `<textarea>` or a `<title>`, where the browser reads
  `<i data-text="1 +">` as text, completion, hover and signature help no longer treat it as a
  Datastar expression. The checks already read it as text there. Inside an `<svg>`, where a
  `textarea` holds elements, both still read the expression.
- IntelliJ no longer offers attribute names after a space inside a quoted value whose quote is
  still open, as VS Code already did not: `<textarea><i data-text="1 + d` is text.
- In an HTML string in Kotlin, a template comment such as `{# #}`, `<#-- -->`, `<%-- --%>` or
  `{{! }}` hid the markup in it from the checks, though the browser reads all of it as text and the
  tags in it as tags. The `data-*` attributes there are checked now. In template and HTML files the
  checks still skip them, as a template engine removes them.
- In a template file, completion, hover and signature help no longer read a `data-*` value inside a
  template comment, which the checks skip.
- The checks and the caret read markup the one way in both editors, the browser's: an `<svg>` right
  inside a MathML `annotation-xml` is an svg, and `</foreignObject>`, or any end tag the browser has
  no rule of its own for, closes nothing when a `<div>` stands between it and its element. Before,
  the caret read both otherwise.
- In an HTML string in Kotlin, completion, hover and signature help find the expression in a tag
  whose name is a template, `"<$tag data-text=\"...\">"`, as the checks did.
- `interpolate` reads markup by the same walk, so it follows the browser in these places too. In a
  few nestings of svg and math it now judges a value more strictly, and an `Html` value such as
  `<div></span></div>`, whose `</span>` its own `div` stops, is written as markup where it was
  escaped.

## [0.19.0] - 2026-11-06

### Added

- In the analysis, for editors: `AnalyzeOptions.htmlDsl`, `core` and `foreignCalls`, what the host
  knows of the project. With `htmlDsl = false` the DSL's calls are somebody else's, and no fix
  writes a helper; with `core = false` no call is Streamlord's, no fix or message sends the author
  to `interpolate`, `Trusted` or `Html`, which are no longer read as markup, and a call or a value
  of unknown type written into element text is a hint, since it may be markup the author built,
  while a known String keeps its warning. `foreignCalls` names the calls a host has resolved to
  another library, such as the official Datastar SDK's `patchElements`, whose strings are then read
  as any string handed to an unknown function, and which declare no signal for the hyphen checks.
  `attributeDoc` takes `withKotlin`, as `actionDoc` does. Left at their defaults they change
  nothing but the fixes below. Code that calls streamlord-analysis needs recompiling: the
  constructor and `copy` of `AnalyzeOptions` take the three new properties, and `attributeDoc` its
  third parameter; Kotlin source compiles as before.
- The IntelliJ plugin tells the analysis which of Streamlord's modules each module of the project
  has, through IntelliJ's own record of its libraries, and names the calls it resolves to another
  library. In a project without streamlord-html a call named as the DSL's is somebody else's: its
  string gets no Datastar diagnostics, completion, hover, parameter info or colours, the spell
  checker reads it as prose, no fix writes a helper, and hover leaves the Kotlin helpers out.
  Without streamlord-core no call is Streamlord's, and no fix or message sends the author to
  `interpolate` or `Trusted`. The live templates are offered only in a module that has the modules
  each one writes calls of. A file moved to another module is judged by that module at once.
  While IntelliJ indexes it cannot tell, and the plugin behaves as before. The plugin now depends
  on IntelliJ's bundled Java plugin, which the Kotlin plugin needs already. The analysis gains
  `Analyzer.dslNames`, the calls streamlord-html alone defines, so an editor leaves out the same
  calls the analysis does.
- In the analysis, for editors: `Analyzer.attributesIndexes` finds each kotlinx.html
  `attributes["data-..."] = "..."` of a file, and `expressionAtCaret` reads the expression in its
  value, so completion, hover and signature help work there in both editors. The IntelliJ plugin
  also colours that form, spells only the strings of its expression, and gives a call named as the
  DSL's that it resolves elsewhere no colours, completion, hover or spelling, where the call is on a
  receiver, its name is declared or imported from elsewhere in the file, or it has an issue.
- The VS Code extension reads from a Kotlin file's imports whether it can call streamlord-html: a
  DSL-named call in a file without the DSL's import gets no Datastar diagnostics, completion,
  hover or signature help, reads no signal, and is not coloured as Datastar. In a file that
  imports the official SDK and nothing of Streamlord no call is Streamlord's. It colours
  kotlinx.html's `attributes["data-..."] = "..."` too, for the catalog's attributes only.

### Fixed

- The build arguments on the native-image page failed with Ktor 3.6.1, on some twenty
  `kotlinx.coroutines` classes "unintentionally initialized at build time": Ktor 3.6.1 reads
  `Dispatchers.IO` as `io.ktor.utils.io.jvm.javaio.ReadingKt` is initialized, and the list
  initializes `io.ktor` at build time. The list now ends with
  `--initialize-at-run-time=io.ktor.utils.io.jvm.javaio.ReadingKt`.
- Both editors coloured every `data-*` attribute in HTML as Datastar, the page's own `data-color`
  and a typo such as `data-onn` among them. Only the catalog's attributes are coloured now, and
  in VS Code only the value of one that takes an expression is coloured as an expression.
- The VS Code extension coloured no Datastar attribute whose value sits in single quotes, or in
  escaped quotes in a plain Kotlin string, `"<div data-show=\"\$open\">"`.
- The signal index of both editors read a helper called on a receiver, such as
  `headers.set("Cache-Control", "no-cache")` or `map.set("count", 1)`, as defining a signal. In
  code that cannot call streamlord-html, the DSL's short helpers (`set`, `setExpr`, `toggle`,
  `signal`, `increment`, `decrement` and `not`) are somebody else's too: they define no signal
  and completion offers no name from them. A `data*` helper still defines its signal there, so a
  `dataSignals` you wrote yourself keeps working. The analysis gains overloads of
  `collectSignals`, `collectSignalDefinitions` and `signalDeclarations` that take the names of the
  calls to leave out.
- In the IntelliJ plugin, go to declaration, find usages and rename now find a signal defined by a
  `data*` helper you wrote yourself, or by the official Datastar SDK's `patchSignals`, which they
  left out. A `$name` whose only definition they
  leave out, such as somebody else's `set`, is no longer a link that says "Cannot find
  declaration", and rename no longer reports a conflict with such a definition.
- The IntelliJ plugin's spell checker failed on a Kotlin string whose expression has a template
  right after an empty string, such as `dataText("''$count")`, and IntelliJ reported the error.
- In the IntelliJ plugin, HTML handed straight to a call of another library, such as the official
  Datastar SDK's `patchElements`, got no diagnostics at all: the plugin dropped every issue of a call
  that resolved elsewhere. It is judged as any HTML string now, so `data-onn` is reported there,
  while the rules of a Streamlord patch, an id on each element among them, are not applied.
- In both editors, `attributes["data-on:click"] = "$count++"` was offered `increment("count")` and
  told to write `signal(...)` in a file with nothing of streamlord-html in it. A helper is offered
  there only when the file imports from the DSL, or the host says it is there. In that form and at
  a DSL call alike, none is offered when an import from elsewhere, such as Hamcrest's `not`, takes
  its name, and a helper is offered only for a literal it says whole: the JVM analysis also offered
  `signal("count")` for `"""$count` with a line break before its closing quotes, and dropped the
  line break. Every helper fix now imports the helper, unless the file imports it, the whole DSL,
  or is in its package.
- `attributes["data-bind"] = "$count"`, and the same for `data-indicator` and `data-ref`, was
  offered `signal("count")` and escapes that each led to a new warning. These attributes take a
  name: the one fix now writes the literal without the dollar, `count`, and says so, and a path such
  as `$user.loading` or `form.$name` stays as written. A constant or a number there is the name the
  author chose and gets no issue, as in markup.
- An import a fix added went above a `#!` line, `@file:` annotations or a licence comment in a
  file without a package or imports, and an `import` line inside a raw string or a comment was
  taken for one. It goes after them now, a licence in `//` lines stays whole, and only code counts:
  a package line after a comment or an annotation is found, and an import with a comment after it
  is the import. The import is written with the file's own line break.
- The two editors read a file's imports alike: a line starts after a line feed and is indented
  with spaces and tabs, where the JVM analysis also took a NEL as a line break and VS Code a
  no-break space as indentation.
- A Streamlord call claimed every string handed to it, so `patchElements(header, "<p>$name</p>")`
  and `patchElements("<p>$name</p>") { }`, whose HTML it never reads, were not judged at all. Only
  the strings a call reads are its own now; the rest are read as free strings, for the values
  written into them and their signals, while a script, a selector or a helper's name is still the
  call's own.

## [0.18.0] - 2026-11-03

### Added

- Both editors go to a signal, find where it is used and rename it. Go to declaration on `$count`
  lands on every place the project defines it, in markup and in Kotlin; find usages lists those
  and every expression that reads it; rename rewrites them all, each as its place needs, and stops
  before writing anything at a place that cannot hold the new name. In `$user.name.length` each
  part is its own signal. The IntelliJ plugin answers from positions its index keeps, so a search
  reads no file again; on ktor the first search takes about 1 s and each one after it 1 to 2 ms.
  In IntelliJ this covers templates read as plain text too, and beside the official Datastar
  plugin a place is listed once.
- In the analysis, for editors: `signalDeclarations` and `SignalDeclaration`, every place a source
  defines a signal with the offsets of the name as written and its `Kind`; `signalRenameText` and
  `signalReadRenameText`, what a rename writes at a defining place or a read, or null where nothing
  can.

## [0.17.0] - 2026-11-01

### Added

- Both editors help inside a Datastar expression. In the options object of a backend action,
  `@get('/x', {|})`, completion offers the options not yet written and hover says what each does,
  from a `doc` each option now has in the catalog, written from Datastar 1.0.4's `fetch.ts`.
  Parameter info in IntelliJ and signature help in VS Code show an action's parameters with the one
  the caret is on. After `data-on:` come Datastar's own events, `datastar-fetch` and
  `datastar-signal-patch`, which the catalog now lists as `datastarEvents`, and the DOM's, with
  their pages on MDN where TypeScript names one, from `catalog/dom-events.json`, which `npm run
  build` generates from the `lib.dom.d.ts` of the TypeScript the extension pins and a test holds to
  it. In IntelliJ the list shows each item's documentation (F1 on macOS, Ctrl+Q elsewhere). In
  Kotlin the help starts once the DSL call's parenthesis is closed, as the IDE closes it for you.
- The editors flag a `data-nonce` on `<html>` that stops Datastar: `missing-nonce`, an error, for
  an empty one, which makes the client throw `NonceRequired`; `invalid-nonce`, a warning, for one
  that is no base64 value, which a browser ignores in the policy. A templated value says nothing.
  `dataNonce` of such a literal, which throws as the page renders, is an error either way.
- `CspNonce.problem(nonce)`: why a nonce cannot be one, or null, in the words `CspNonce.checked`
  now throws with.
- In the analysis, for editors: `valueAtCaret`, the attribute value a caret stands in, read by
  core's `holeLandings`; `actionCallAt`, the action call, argument and option key at a caret;
  `expressionAtCaret` and `ExpressionAtCaret`, the Datastar expression before a caret in markup
  or Kotlin; `ActionCall` and `OptionKeyAt`; `proseRanges`, the strings of an expression;
  `ActionSpec.parameters`; `fetchOptionDoc`; `Catalog.datastarEvents` of `DocumentEvent` and
  `Catalog.domEvents` of `DomEvent`.
- The IntelliJ plugin's spell checking reads only the strings of a Datastar expression, in HTML and
  in the DSL calls and HTML strings of Kotlin, so a signal name such as `$usrnm` is no typo and
  `'Sav'` still is. A template file IntelliJ reads as plain text is still spelled whole.

### Changed

- `FetchOption` in the analysis gains `doc`, which replaces its three-argument constructor and
  `copy`. The analysis is read by the editors only.
- The VS Code snippet and the IntelliJ live template of `data-nonce` and `data-preserve-attr` ask
  for a value where they asked for an expression, since Datastar takes their value as text.

### Fixed

- Completion in both editors read an attribute value from its own line only, and only in double
  quotes, so it offered nothing in a value over several lines, such as a `data-signals` object, or
  in single quotes. It reads the value as the browser does now. In a Kotlin string, a signal written
  `\$` completes too and keeps its backslash.
- The IntelliJ plugin listed every Kotlin and markup file of the project again after each
  keystroke, to find the signals other files define, asking the IDE once for each template extension
  in its settings. That cost 150 to 330 ms a keystroke whatever the project's size, one file or
  seventy thousand. It lists them again only when files change on disk, when a file is saved, when
  the project's roots or file types or the plugin's settings change, or when the IDE finishes
  indexing. A keystroke costs under 5 ms in a small project and 5 to 17 ms in one of a few thousand
  Kotlin files or more.

## [0.16.0] - 2026-10-31

### Added

- `DatastarAttributes.value(value)`: a value as kotlinx.html must be handed it for the browser to
  read it back exactly, for an attribute you write yourself (see Fixed).
  `DatastarAttributes.expression(expression)` does the same for an expression, after the checks
  every DSL helper makes, which the free and the Pro helpers now share.

- `Html`, the markup `interpolate` returns, and `patchElements`, `respondElements` and
  `datastarElements` overloads that take it. In another `interpolate` it is written as markup in
  element text and judged as the text it is anywhere else. Only `interpolate` and `joinToHtml` make
  one. It is markup only where it closes everything it opens, read as HTML and as svg or math, and
  the placeholder it fills is outside any comment, CDATA section or `textarea` or `title` body:
  `interpolate("<script>")` or `interpolate("<a href=\"")` would otherwise turn the value after it
  into a program or a link, so such a fragment is escaped and shows as the markup it is.
- `Iterable<Html>.joinToHtml(separator)`: the markup of each `Html` in turn, as one `Html`, for a
  list of rows as the value of a placeholder. Each piece already closes what it opens, so nothing is
  read again or escaped.
- `PatchElements(html, ...)` and `ElementsResponse(html, ...)`, functions that take an `Html` where
  the constructors take a `String`, for code that builds the event itself, as a `Flow` of events
  does. A constructor taking the `Html` would clash with the `String` one on the JVM; Java callers
  are unaffected.
- Two analysis codes for a Kotlin value in an HTML string, judged by where it lands as
  `interpolate` judges it at run time, with core's reading of the markup (`holeLandings`, internal
  API, which `interpolate` itself now calls): `unescaped-interpolation` where escaping saves it, a
  hint or, for a `String`, a warning, with a fix that rewrites the literal as `interpolate(...)`;
  `unsafe-interpolation` where it cannot, a warning (a hint for a tag or attribute name that is not
  known to be a `String`). VS Code indexes the workspace's `const val`s; IntelliJ adds types.
- `Fix.edits` in the analysis: further replacements a fix makes, such as an import, with
  `Fix.replacements`, all of them in the order to make them, and `Fix.preferred`, whether an editor
  may offer the fix as the one to apply. `AnalyzeOptions.constants` takes the `const val` names the
  workspace declares, and `AnalyzeOptions.holeFact` what the editor knows of a value from its type,
  as a `HoleFact`. `HoleFact.UNSIGNED` is a `UInt`, `ULong`, `UShort` or `UByte`: digits, but no
  `kotlin.Number`, so `interpolate` refuses one in a `data-*` expression or `style`, and the editors
  say so, an error in a `%s` placeholder with a `toLong()` fix and a hint where a Kotlin template
  writes it.
- The editors judge a call to `interpolate`: `interpolate-holes`, an error, for a `%s` count that
  does not match the values; `unsafe-interpolation` on a `%s` whose value it will refuse, an error
  where the value is known to be refused; `interpolate-template` for a Kotlin template written into
  the format, which is neither escaped nor refused, with a fix that makes it a `%s` placeholder. The
  format is read as HTML, and `interpolate`'s `format` is marked `@Language("HTML")`.
- A fix that quotes an attribute value written without quotes.
- `Analyzer.analyzeProject(root)`: every `.kt` file under a folder, `build` and hidden folders
  left out, analysed with the constants the files declare between them, the verdict VS Code gives
  the same workspace. Each `ProjectIssue` carries the file, the line, the column and the `Issue`,
  and `format()` writes it as `path:line:col: severity: message [code]`. `failures()` keeps those
  that should fail a build: every error, and every `unsafe-interpolation` warning. The test over
  your own sources on the analysis page is now one line. `projectConstants(sources)` gives the
  constants alone, from each file's `constantNames` and `otherDeclaredNames`.

### Changed

- `dataAttr` and Pro's `dataAnimate` take an attribute's name as HTML has it and refuse a capital
  in it: the browser lowercases the key, so `dataAttr("viewBox", …)` set `view-box`, and
  `dataAttr("ariaLabel", …)` relied on a guess that it meant `aria-label`. Write
  `dataAttr("aria-label", …)`, and the object form, `dataAttr("{viewBox: …}")`, for an attribute
  that really has capitals. The editors flag the call with the kebab-case fix.
- `AttributeKeys`, `SignalNames` and `rejectDeclaredLengthAbove` in streamlord-core are marked
  `@InternalStreamlordApi`, a new opt-in for the API Streamlord's modules share among themselves:
  the client's rules that the DSL, the adapters and the analysis each read from one place. Code
  outside Streamlord that calls them compiles only with `@OptIn(InternalStreamlordApi::class)`,
  and they may change in any release.
- The catalog reaches core and the analysis as code, generated from `catalog/datastar-1.0.4.json`
  when they build. `ElementsGuard`'s attribute sets, its default prefixes, the attributes
  `interpolate` refuses a placeholder in and `DatastarAttributes.prefix` come from it, where core
  listed them by hand and a test compared the two. The analysis no longer reads the catalog as a
  resource, so a native image that runs it needs no resource configuration for it.

- `interpolate` returns `Html` instead of `String`. The JVM signature is the same, so compiled code
  keeps working, but Kotlin source may need changing:
  - `val s: String = interpolate(...)` no longer compiles: write `interpolate(...).text`.
  - `+interpolate(...)` in a kotlinx.html `unsafe { }` block no longer compiles: write
    `+interpolate(...).text`.
  - A test that compares the result with a string still compiles and now fails, since an `Html`
    is never equal to a `String`, though both print the same: JUnit's
    `assertEquals("<p>x</p>", interpolate(...))` and AssertJ's
    `assertThat(interpolate(...)).isEqualTo("<p>x</p>")`. Compare `.text`.
  - On Spring, `ResponseEntity.ok(interpolate(...))` still compiles, but Spring has no message
    converter that writes an `Html` as markup. Return `datastarElements(html)`, or pass
    `html.text` where you build the `ResponseEntity` yourself.
  - From Java, `interpolate` still returns a `String`, so the result of one `interpolate` handed to
    another from Java is escaped, as any string is. Nest the calls in Kotlin, or wrap the inner
    result in `Trusted`.
- A value in a URL the browser loads code from now throws `UnsafeInterpolationException` where
  0.15.0 let a relative or `https:` value through: `<script src="%s">`, `<iframe src>`,
  `<embed src>`, `<object data>`, `<base href>` and `<link href>`. Put the host in the literal,
  `"/js/%s"` or `"https://cdn.example.com/%s"`, or wrap a URL you chose yourself in `Trusted`. A
  value in an end tag, `</%s>`, throws too: write an end tag from literals alone. A `Trusted` value
  in a URL no longer waives the other values in it: wrap the whole URL you chose, or put the host
  in the literal. Security says why.
- streamlord-analysis changes shape where code built against 0.15.0 may use it. `AnalyzeOptions`
  gained `constants` and `holeFact`, and `KotlinString` gained `nested`, which changes their JVM
  constructors and `copy`; Kotlin source compiles as before. `Fix` is no longer a data class: it
  has no `component1()` to `component4()`, so `val (title, start, end, text) = fix` no longer
  compiles, and its constructor and `copy` take `edits` and `preferred`, which Kotlin source
  with four arguments still compiles against, while compiled code does not link. `InterpolationKind`
  has a new entry, `OPERAND`, for a value joined to literals with `+`, so an exhaustive `when` over
  it needs a branch. The casing helpers moved to core's `AttributeKeys`: `toCamel`, `kebab` and
  `wireKeyReads` are gone from streamlord-analysis, and `Catalog` no longer has `prefix`,
  `aliasedPrefix` and `version`, which `CatalogNames` holds. `AttributeSpec` and `Modifier` take
  new catalog fields, which changes their JVM constructors and `copy`, and
  `ExpressionValidator.validate` takes the attribute it judges as a fourth, defaulted parameter.
  Recompile code built against 0.15.
- The editors judge `${...}`, values joined with `+`, literals inside a template and `$$` literals,
  which they skipped, and no longer give every `$name` in HTML the same hint. A constant, a literal,
  a declared number or boolean and markup built in the template say nothing. In a Datastar
  expression, `kotlin-interpolation` no longer calls a `String` fine as a server value: only a
  number or a boolean is.
- `Trusted` writes its markup in a Kotlin template, not `Trusted(html=...)`.

### Fixed

- The editors flag, as `key-refused`, every key and signal name the DSL refuses at runtime: one
  holding `__`, where the client starts reading modifiers, whitespace, a quote or a character that
  ends an attribute; one ending in `_` before any modifier the call adds, its `__case`,
  `ifMissing`, `session` or the modifiers of its lambda; and a signal name no `$` reaches, such as
  `a:b`, `über` or `constructor`, in a key, a pair or the name of `dataBind`, `dataRef` or
  `dataIndicator`. The hint used to say what such a key writes. The reasons come from
  `AttributeKeys.nameProblem` and `SignalNames.nameProblem` in streamlord-core, which the DSL
  throws with.
- `JsLiteral`, which writes the values of `set`, `setAll` and a fetch's `payload` and `headers`,
  refuses what `JsonWriter` refuses: a map that holds itself overflowed the stack, and `1` and
  `"1"` as keys of one map were both written as `'1'`.
  It now walks a value with `JsonWriter` itself, in a JavaScript style, so the two cannot part.
  Half of a surrogate pair in a string is written `\uXXXX`, as `JsonWriter` writes it, where it
  reached the page as U+FFFD.
- `FetchOptions.retry` says what Datastar 1.0.4 does with `never`: a request that never reaches
  the server is retried all the same, as with `auto`, up to `retryMaxCount`. The DSL fixture holds
  it.
- The editors warn, as `scope-name`, on `patch` in an expression whose attribute does not hand it
  over, such as `data-text="patch.a"`: only `data-on-signal-patch` declares `patch`, so elsewhere
  it throws a ReferenceError. (`evt` is declared in every expression, and is undefined outside
  `data-on`.) The catalog says per attribute which names its plugin hands an expression,
  `argNames`, held to the client's plugins.
- `dataSignals("selected" to null)` declared nothing, without a word: Datastar's client holds no
  null, so the null removes the key, and an expression that reads the signal gets `""`, as it does
  for a null in a list. The editors warn on such a pair as `signal-null`, and the signals page says
  what it means for a form whose field is `null`.
- A `\` followed by `&` in an expression lost its backslash: kotlinx.html writes that pair as a
  bare `&`, so `dataText("'\\&amp;'")` showed `&` where it meant `&amp;`, and `'\\\\&'` lost
  one of its backslashes. Every expression the DSL writes now reaches the browser as written.
- `LiveDatastarStream.open` puts the `datastar` parameter where the client's `URLSearchParams.set`
  puts it, in place of one the URI carries rather than at the end, reads `data%73tar` as that
  parameter as a form does, and sends a lone surrogate as U+FFFD.
  It and the editors' request inspector send the request with `ClientRequest` in streamlord-core;
  `Requests.ACCEPT` in streamlord-analysis is gone, as `DatastarProtocol.ACCEPT` says the same.
- `ElementsGuard` and `interpolate` decode character references as the HTML tokenizer does, with the
  decoder the analysis uses: `&nbsp;` is a no-break space, `&#0;`, a surrogate and a number past
  U+10FFFF are U+FFFD, `&#128;` to `&#159;` are windows-1252, and `&amp`, `&lt`, `&gt`, `&quot` and
  `&nbsp` decode without their semicolon. `data-text="&nbsp;"` is refused now, as `&#160;` was.
  The typo rule for `data-*` names is one function, `ElementsGuard.nearAttribute`, which the
  analysis calls.

- The editors no longer report `<p> has no id` inside `<$tag ...>`, whose template is a tag name,
  nor text fragments in a `trimMargin` literal, whose margin is not text.
- A template inside a JavaScript string of a Datastar expression, `@post('/x/$id')`, was called
  safe text. It is code Datastar runs: a warning now.

- The exception for a value in a URL that loads code said to pass a relative or `https` URL, which
  is what had been passed. It says now what the rule is: write the host into the markup before the
  placeholder, as in `"/js/%s"` or `"https://cdn.example.com/%s"`, or wrap a URL you chose in
  `Trusted`. `UnsafeInterpolationException.loadsCode` tells such a refusal apart; its constructor
  takes it as a fourth parameter, which changes its JVM signature.
- `href` of a `<link>` loads what it points at, a stylesheet, a module or an icon, so a value there
  is held to the host rule above, whatever the `rel`: `<link rel="stylesheet" href="%s">` with
  `https://evil.example/x.css` was let in. A `content` of a `<meta>` that starts with a time, as
  `http-equiv="refresh"` reads it, has its URL held to the URL schemes, so
  `content="0;url=%s"` refuses `javascript:`; a `content` that is no refresh, a description say,
  takes any value as before. Both were in 0.15.0.
- `ElementsGuard`, `interpolate` and the analysis searched to the end of the markup for both of a
  comment's closing sequences at every comment, so a patch of many comments took time that grew
  with its square: 800 KB of them took seconds in the guard, on every patch. `interpolate` read the
  markup before a placeholder again for every `Html` value (20000 of them took the better part of a
  minute), followed every end tag that matched nothing through the open elements, and the guard
  searched every CDATA section of an svg for every svg tag, and the editors lowercased the whole
  markup for each raw-text end they looked for. Each is one walk now. This was in 0.15.0.

### Security

- `interpolate`, and the editors with it, took an `=` where an attribute name begins as the start of
  a value, where the browser's tokenizer takes it as the first character of the name, which then
  runs to the next space, `/` or `>`, quotes included. So in `<img src=x ='%s'>` the placeholder was
  judged to be in a quoted value and let in, and the value ` onerror=alert(1) x` came out as an
  `onerror` the browser runs. A name now always takes its first character, `=` or not, and a value
  there is structure. A smaller misreading went with it: a `/` that ends an unquoted value, as in
  `<svg x=a/>`, is part of the value and does not close the element. This was in 0.15.0.
- `interpolate`, `ElementsGuard` and the editors ended a `<script>` at its first `</script>`, but
  the browser's tokenizer does not always: after a `<!--` in the body, a `<script` start makes it
  double-escaped, where `</script>` only returns it to escaped, until a `-->` or a second
  `</script>`. So in `interpolate("<script><!--<script></script>%s", value)` the placeholder was
  judged to be in text and let in, and the value came out inside the script, which the browser runs.
  A script body now ends where the tokenizer's script data states end it, escaped and double-escaped
  included, so a value there is code and refused, and an `Html` that leaves a script open this way
  is escaped. This was in 0.15.0.
- `interpolate` read the markup three fixed ways, all HTML, all `<svg>`/`<math>`, and all svg or
  math with CDATA sections as text, and took the strictest, but the browser switches reading as its
  element stack changes and no one fixed way follows it. In
  `<svg><![CDATA[><!--]]></svg><![CDATA[><img %s>-->]]>` the first `<![CDATA[` is a section inside
  the svg, but once `</svg>` returns to HTML the second is a bogus comment the first `>` ends, so
  the `<img %s>` after it is a live element and the value its attributes; every fixed reading saw
  the img inside a comment or a section and missed it. A fourth reading now follows the browser's
  stack, svg and math nesting, their HTML and MathML-text integration points, and the tags that
  exit foreign content, and switches CDATA, bogus-comment and raw-text rules by position; it joins
  the three as a conservative floor, and the strictest still wins. This was in 0.15.0.
- A value in a URL that loads what the browser runs, `src` of `<script>`, `href` of `<base>`, `src`
  of `<iframe>` and `<embed>`, `data` of `<object>`, and `href` of an SVG `<script>`, is refused
  unless the literal before the placeholder already fixes the host: a path-absolute `/x` (a single
  `/` and then a character that is not `/` or `\`, so a value cannot make it `//host`), a
  same-document `#` or `?`, or a whole origin `scheme://host/` with a safe scheme. A safe scheme
  alone no longer settles such an attribute, since `https://` can still point at another origin, and
  a single leading `/` no longer settles its host, since the next character could be a second `/`.
  `Trusted(...)` passes as ever. `urlHostFixedBy` (internal API) answers it, and `HoleLanding`
  (internal API) carries `loadsCode` for such an attribute, so the editors judge a value there by
  the host too: a Kotlin template that may move it is an `unsafe-interpolation` warning, with no
  rewrite to `interpolate`, which would refuse it, and a `%s` placeholder in an `interpolate` format
  whose literal does not fix the host is an error where the value is known, since only `Trusted`
  passes.
- `interpolate` decoded each literal piece of a URL attribute on its own before it judged the
  scheme, where the browser decodes the attribute once it is whole, so a character reference could
  begin in the markup and end in the value: `<a href="&%s">` with `#106;avascript:alert(1)` wrote
  `&#106;avascript:`, a `javascript:` link. The URL is judged as it will be written now, the
  literal as it stands and each value escaped, decoded once. This was in 0.15.0.
- `interpolate` refused a value in a start tag's name but let one into an end tag's,
  `</%s>`, where it chose which element to close. It is refused there too.
- `interpolate` read an end tag as text, or as ending at its first `>`, where the browser reads
  its attributes as a start tag's and a quoted value can hold a `>`: in
  `</p x="><b title="><script>%s</script>">` it took the value for an attribute, and the browser
  ran it. It and the editors read an end tag's quotes now, and a value anywhere in an end tag is
  refused as structure, since the browser throws its attributes away.
- `interpolate` read an end tag with Unicode case folding, where the browser folds A to Z alone:
  in `<script>x</ſcript>%s</script>` (U+017F, a long s) it took the value for text after the
  script, and the browser ran it as part of the script. It is refused now, as every value inside a
  script is. `<ſcript>` is no script either, and its content is text. Only a format that holds such
  a tag itself was affected; a value cannot add one.
- Inside an `<svg>` or a `<math>`, `interpolate` and `ElementsGuard` read `<![CDATA[` as HTML does,
  as a bogus comment the first `>` ends, where the browser reads a CDATA section that only `]]>`
  ends: in `<svg><![CDATA[ > <x a="]]><img onerror='%s'>">` the img is an element of the page,
  and its handler held the value. Both now read the markup that way as well, and take the
  strictest of the readings: inside an svg's `title` or `desc`, or after a tag that ends the svg,
  the browser is back in HTML and reads a bogus comment again, so neither reading is right
  everywhere. An `</svg>` inside a CDATA section no longer ends the part `ElementsGuard` reads as
  svg either.
- `interpolate`, `ElementsGuard` and the analysis read a `<noscript>` as text, as a browser with
  scripting on does, but the client parses a patch with `DOMParser`, where scripting is off and a
  noscript holds elements. So `interpolate("<noscript><xmp><a title=\"</xmp><script>%s</script>", v)`
  and `<noscript><textarea></noscript><p title="</textarea><img src=x onerror='%s'>">` let the value
  into a script and a handler. A noscript is markup to all of them now, and `interpolate` reads
  markup that holds one with scripting on as well, as a page loads it, and takes the stricter. This
  was in 0.15.0.
- An `Html` that left an element open counted as closed, though an open `<svg>` or `<math>` changes
  how the markup after it is read: in
  `interpolate("%s<title><xmp><!--</xmp><script>%s</script>", interpolate("<svg>"), v)` the title
  became an svg one that holds elements and the script ran. An `Html` is markup now only where it
  leaves open no svg, math or element in them, no `template` and no `select`, and ends no element
  it did not open; otherwise it is escaped. And `interpolate` reads every format inside an `<svg>`
  and inside a `<math>` too, as the client parses a patch in those namespaces, where a `<title>` or
  an `<mi>` holds HTML: `<title><xmp><!--</xmp><img src=x onerror="%s">` is refused now. The
  element-stack reading also follows a `<font>` with a `color`, `face` or `size` out of foreign
  content, keeps `<mglyph>` and `<malignmark>` MathML inside an `<mi>`, and takes the text of an
  svg `<script>` or `<style>` for code though they hold elements. `Html` is new in this release;
  the formats read wrong inside an svg or a math were in 0.15.0.
- One `Trusted` value in a URL attribute waived the URL check for every other value there:
  `interpolate("<a href=\"%s%s\">x</a>", Trusted(""), "javascript:alert(1)")` wrote a `javascript:`
  link. A `Trusted` value now counts toward the URL as the literal does, written as it stands, and
  the whole URL is judged; only a URL with no other value in it goes unjudged. The host rule for a
  URL that loads code reads a `Trusted` value before the placeholder the same way. This was in
  0.15.0.
- The readings from inside an `<svg>` and a `<math>` kept every HTML element open to its end tag,
  where the browser closes some by itself: a `<p>` at the next `<p>` or `<div>`, an `<li>` at the
  next `<li>`. Once the element opened from the svg is closed, the browser is back in the svg, so
  in `interpolate("<p><p></p><script><!</script>%s", v)` the script was an svg one whose `<!`
  swallowed its end tag, and the value came out inside it. The element-stack readings now follow
  the tree builder's implied end tags (a `p` closed by the elements that close one, an `li`, `dd`
  or `dt` by the next, a heading by a heading, an `option` by an `option`), drop the `html`, `head`,
  `body` and `frameset` start tags and, in body, the parts of a table outside one, and take a `</p>`
  or `</br>` in foreign content out of it, as the browser does. The readings from an svg and a math
  are also taken once more with no HTML element opened from them left open, and the strictest still
  wins, as the editors do too. These readings are new in this release.
- The element-stack readings put an `<svg>` tag in the svg namespace wherever it stood, and a
  `<math>` in MathML, but inside foreign content a tag takes the namespace it stands in: in a math
  patch, `<xmp><svg><mi><textarea><a href='</textarea><img src=x onerror=%s>` has a MathML `mi`,
  whose content is HTML, so the textarea ends and the value lands in a live handler, where the
  readings saw an svg `mi` and a quoted attribute. Inside an svg or a math, an `<svg>` or `<math>`
  now stays in the namespace around it. These readings are new in this release.
- `interpolate` and the editors took a value inside a comment or a bogus comment for text, but
  escaping takes only its `<` and `>`, and its dashes can still change the comment around it: in
  `interpolate("<!%s><a title=\"--><script>%s</script>\">", "-- x", v)` the value made `<!` a
  comment that ran to the `-->` in the title, and the script after it was live. A value right after
  `<!` or `<!-`, where a value can finish a comment or a `<![CDATA[`, and one in a comment or a
  CDATA section whose markup goes on with `>`, `!>`, `->`, `-!>` or `]>`, which a value's dashes,
  `!` or `]` would make an early end, is refused as structure. This was in 0.15.0.
- A `Trusted` value was written as it stands but never read back into the markup, so one in a tag or
  attribute name, or anywhere else that changes how the markup is read, waived the checks on the
  values beside it: `interpolate("<button %s=\"%s\">", Trusted("onclick"), v)` put `v` in a handler,
  and `<%s>%s</%s>` with `Trusted("script")` put it in a script. `interpolate` now reads the markup
  as it will be written, each `Trusted` value's text in place, and judges every other value where
  that puts it, URL and host checks included; a `Trusted` value itself is still not judged.
  `holeLandings` (internal API) takes the text of each value already known, so the editors can read
  it the same way. This was in 0.15.0.

## [0.15.0] - 2026-10-28

### Added

- `dataAnimate` takes the object form, `dataAnimate("{width: $w + 'px'}")`, and the modifiers the
  Pro plugin reads: `duration`, `delay`, `ease` (an `Easing` enum of the plugin's names), `loop`
  and `pingpong`. `RafModifiers` gains `delay`, `debounce` and `viewTransition`, `ResizeModifiers`
  `delay` and `viewTransition`, as the two plugins read them. The catalog lists the same. The key
  form's JVM signature gained the modifier argument; Kotlin source compiles as before.
- `SignalsCodec.toJsonValue(value)` encodes a value with the codec and returns it as a
  `JsonValue`, and `dataSignals(JsonValue)` writes a JSON object as `data-signals` with its keys
  as they stand. A page declares its signals from the class its handler reads:
  `dataSignals(call.streamlord.codec.toJsonValue(SearchSignals()))` gives the names
  `readSignals<SearchSignals>()` reads, `@SerialName` and `@JsonProperty` included. A field that
  is `null` declares nothing, since the client removes a signal patched to `null`.
- The analysis reads `dataSignals(codec.toJsonValue(...))`. The drift check and the unknown-signal
  check count the properties of a `@Serializable` class named there, by a constructor call, a type
  argument or the type of a variable the file declares, as declared. For a class they cannot read,
  such as a Jackson one, or a value whose class the file does not say, every name counts as
  declared, in the whole project; `SignalDriftReport.declaredByCodec` says when that happened.
  `SignalFacts` gained `encodedTypes` and `SignalDriftReport` `declaredByCodec`, which changes their
  JVM constructors, and `collectSignalDefinitions` returns two kinds of entry that are no signal
  names, `class:Form` and `toJsonValue:Form`, which `isKnownSignal` reads.
- The analysis and both editors flag a hyphen in a signal name written in a DSL call, which the SDK
  refuses at runtime, as the error `signal-name-hyphen`, with the camelCase fix:
  `dataSignals("foo-bar", …)`, `dataBind("form.first-name")`, a pair's key, `removeSignals("a-b")`,
  and a hyphen in `signal`, `set` and the other expression helpers that the client reads as part of
  a signal name or as a minus, with the bracket form, `form['first-name']`, where it applies. A
  call inside a string template counts too, `"${signal("is-running")} ? …"`, and a name with a
  template in it, `"item-$id"`, is reported without a fix. `set` and `toggle`, whose names a map or
  Ktor's `headers { }` share, count only inside the arguments of a DSL call. The analysis collects
  a helper's name as written, where it read `dataSignals("foo-bar" to 1)` as `fooBar`. In IntelliJ
  the check is part of Datastar key casing.
- `SignalNames.hyphenProblem` says why a name with a hyphen is refused as a signal name, and
  `SignalNames.withoutHyphens` gives the camelCase name to write instead.
  `SignalNames.withoutReferenceHyphens` does the same for a reference written after `$`, read as
  the client reads it, and is what the expression helpers and the analysis hold a name to.
  `SignalNames.referenceAsSubtraction` gives the expression to write where such a hyphen comes
  before a digit or a `$` and looks like a subtraction: `$items[$i - 1]` for `items[$i-1]`.
- `ElementsGuard.evaluatedAttributes`: the attributes whose value Datastar runs as JavaScript,
  the expression attributes and the four signal filters.
- `conformance/`: what Datastar's client and the servers actually do, as fixtures the tests hold
  Streamlord to. `conformance/client/` is the client's own functions run over fixed inputs
  (casing, attribute keys, expression rewriting, modifier tags, the signal store),
  `conformance/dom/` the client run in a DOM (what each frame did to a page and its store, what
  the parser does with a script body), both generated from `datastar-reference/` by scripts in
  `conformance/generate/`. `conformance/wire/` is the exact HTTP answer of each adapter, per
  server, for a fixed set of requests, rewritten with `updateTranscripts` when it is meant to
  change. CONTRIBUTING.md says how a new Datastar version goes in.

### Changed

- `LiveDatastarStream.open` sends the page's signals as the `datastar` query parameter, `{}` unless
  its new `signals` argument says otherwise, as the browser's `@get` does. A handler under test
  that reads signals now gets an object, where it got none; a test that relied on none fails, and
  says so. The function's JVM signature changed with the argument.
- `interpolate` admits a value into a `data-*` attribute the client reads as it stands:
  `data-nonce`, `data-preserve-attr`, `data-ignore`, `data-ignore-morph`. A page written as a string
  can take its CSP nonce through a placeholder. The expression attributes and the signal filters
  (`data-json-signals`, `data-on-signal-patch-filter`, `data-persist`, `data-query-string`) are
  refused as before, since the client evaluates those, and so are `data-bind`, `data-ref` and
  `data-indicator`, whose value is a signal path the client follows without a guard: `__proto__.x`
  there writes into every object of the page.
- A signal has one name, the one you write, in Kotlin and in the browser. A name may no longer hold
  a hyphen, since the client reads one as a capital in an attribute key and as a hyphen in an
  expression or a patch: in 0.14.0 `dataSignals("foo-bar" to 1)` declared `$fooBar`, and
  `patchSignals("foo-bar" to 1)` patched `foo-bar`. `dataSignals`, `dataComputed`, `dataBind`,
  `dataRef`, `dataIndicator`, `dataMatchMedia`, `patchSignals` and `removeSignals` refuse such a
  name, and `signal`, `set` and the other expression helpers refuse a hyphen that the client reads
  as part of a signal name or as a minus. They read the name as the client reads `$` and the name,
  so a quoted key in brackets is text whatever it holds, `a['x]y-z']`, while `items[$i-1]` reads
  the signal `i-1` and `a['x'].b-c` subtracts `c`. The text of a template literal is text too. The
  exception names what to write, `fooBar`, or `form['first-name']` for a key with a hyphen, and for
  a hyphen before a digit or a `$`, which looks like a subtraction, the expression with the
  subtraction in it, `$items[$i - 1]`; the editors offer no fix for that one. The expression
  helpers write the name as they get it, where `signal("foo-bar")` gave `$fooBar`. A key with a
  hyphen inside a value is data and is written as it stands: `"user" to mapOf("first-name" to "")`
  declares `user['first-name']`, where 0.13.0 and 0.14.0 camel-cased it to `user.firstName`.
- `dataSignals`, `dataComputed`, `dataRef`, `dataIndicator` and `dataMatchMedia` lose their `case`
  argument, and `BindModifiers` its `case`: the DSL writes the key that comes back as the name,
  so a `case` could only give the signal another name. `dataBind`, `dataRef` and `dataIndicator`
  always write the name in the value. `dataOn` and `dataClass` keep `case`.
- `intl(...)` writes an options object for `RELATIVE_TIME` and `DISPLAY_NAMES` even when empty,
  because the client reads a field of it and throws without one.

### Fixed

- `ExecuteScript` lowercased the `</script` it escaped: `'</SCRIPT>'` in a script reached the
  browser as `'<\/script>'`, whose value is `</script>`, and `<SCRIPT` after a `<!--` as
  `<sCRIPT`. The escape now keeps the letters as written. `</BODY>`, `</HEAD>` and
  `</HTML>` are left alone, since the client's test for a whole document reads lower case only.
- The analysis named the wrong signal for a hyphenated reference: for `$a-b-1` it said the key
  `a-b-1` declares `$aB1` and offered that as the fix, where the client camel-cases only a hyphen
  before a lowercase letter and declares `aB-1`. The message and the fix follow the client. The
  IntelliJ highlighter and the VS Code grammar mark `$123` and `$foo-bar` as one signal each, as
  the client reads them.
- The key-case hint in the analysis and the VS Code extension offered a key no case gives back
  for an event or class such as `dataOn("fooBar-baz", …)`. It now says that no key gives the name
  back, and names what the client would read. Found by the client oracle.
- The Spring MVC recipe for a page with a CSP nonce produced `text/html;charset=ISO-8859-1`:
  `produces = [MediaType.TEXT_HTML_VALUE]` lets Spring MVC pick Latin-1. The recipe on the Security
  page and in `CspNonceFilter` says `produces = ["text/html;charset=UTF-8"]`. Found by the wire
  transcripts.
- `JsLiteral` rounded a `JsonNumber` through `Long` or `Double`, so `set("id", json)` changed a
  number past `Long` or with more digits than a `Double` holds. It writes the digits as they are.
- The analysis took `signal("cuont")` for a declaration of `cuont`, so a misspelt name in `signal`,
  `increment`, `decrement` or `not` was never reported. These helpers now read the signal they
  name, in a file that imports the DSL, when the call has no receiver and no import from elsewhere,
  such as Hamcrest's `not`, takes the name; an unknown one gets the same warning and fix as
  `$cuont` in an expression; `set`, `setExpr` and `toggle` still declare theirs, since they assign
  it. A bracketed name, `signal("form['x']")`, is not checked, and the fix offers only a plain
  dotted name. The IntelliJ plugin also leaves out a call that resolves outside Streamlord.
- The analysis counted the keys inside a pair's value, `"user" to mapOf("name" to "")`, as
  top-level signals. Only the pair's own key counts, and the keys of a map handed over as the
  signals, `patchSignals(mapOf("count" to 0))`.
- `SseDecoder` turned an `outer` or `replace` patch without a selector whose elements were a
  comment alone, or text holding a `<`, into an event; the client patches nothing for either. A
  comment ends where the HTML parser ends it, at `-->`, at `--!>`, and at once after `<!-->` or
  `<!--->`, so the elements after one of those are an event.
- Documentation held to the client: a 400 from an over-long query is retried under
  `retry: 'error'` or `'always'`; a form sent with `@get` or `@delete` reads as no signals rather
  than being refused; `application/json` responses carry no charset; `dataPersist` writes its key
  in kebab case; and the examples in `ElementsGuard` and `Casing` show what the code does.
- The analysis and the VS Code extension say when a signal object under `constructor` or another
  name every object has goes wrong: the client holds it, and the next request writes it into the
  built-in one and sends the page's signals without it.

### Security

- The VS Code extension no longer hangs on a line such as `<a != != … '`. Finding the attribute
  name being typed took time exponential in the line's length (CodeQL `js/redos`), so 400
  characters before the cursor could keep the extension host busy for minutes. The tag is now
  read in one pass; completion offers the same names it did.
- The install page says why a scanner on a Spring Boot application reports an older
  `jackson-databind` than Streamlord asks for, and how to raise it. Boot's dependency management
  wins over the codec's version, and every released Boot pins one with a published advisory:
  3.1.7 and 2.21.7 on Boot 4.1.2 and 2.21.4 on Boot 3.5.16, which have CVE-2026-110879, fixed in
  3.1.8 and 2.21.8. The page gives Boot 4's and Boot 3's properties, whose names differ, for Maven
  and for Gradle with `io.spring.dependency-management`, where a `platform(...)` line for Jackson
  has no effect. Streamlord 0.13.0 and older ask for 3.2.3 and 2.22.3, which have it too.
- streamlord-live, the service behind the documentation site, runs on distroless Debian 13.
  Debian 12's distroless image had not taken Debian's `libssl3` fixes, among them one rated high.
- The install page tells a user on Ktor 3.0's Tomcat or Jetty engine to raise them as it already
  did for Netty: Tomcat to 10.1.61, and Jetty to 11.0.26, whose four remaining advisories are fixed
  only from Ktor 3.5. The adapter's tests on Ktor 3.0 run on those versions.

## [0.14.0] - 2026-10-23

### Added

- `assertNoPatchElements` on `DatastarEvents`: that no element patch matches every argument
  given, the arguments being those of `assertPatchElements`, and with none, that the stream
  patched no elements. The counterpart to `assertNoSignal`.
- `assertEventTypes` on `DatastarEvents`: that the stream was exactly these kinds of event, in
  this order, whatever each carried, for a test where `assertExactly` would spell out every event.
  `ExecuteScript::class` and `DatastarEvent::class` throw `IllegalArgumentException`, since no
  event comes back from the wire as either.
- `responseHeaders` on `LiveDatastarStream`: the headers the server answered `open` with, matched
  without regard to case, and empty for a stream read through `of`. `open` still returns only on
  a `200 text/event-stream`; on anything else its failure now prints the answer's headers, a
  cookie's value held back, and the first KiB of its body, waiting at most a second for it.
- The testing page shows a Spring WebMVC route tested through `MockMvcTester`, with a helper that
  sends a request as Datastar's client does and dispatches a `suspend` controller's stream, which
  `exchange()` alone leaves empty, where each kind of failure shows up, and the race below; and a
  WebFlux route through `WebTestClient`. Both are compiled with the documentation and mirrored by
  tests in `streamlord-spring`.
- Two routes on streamlord-live, the service behind the documentation site, for the front page
  to run. `GET /feed` is the hero's code example as a handler: it reads `heads` from the signals,
  appends one `li` to `#feed` and patches `heads` back one higher, with the frames for a page
  that sends `wire`. `POST /judge` is the editors' analysis behind a route: it reads the `source`
  signal, judges it with `analyzeHtml` when it starts with `<` and `analyzeKotlin` otherwise, and
  patches the verdict into `#verdict`, each issue in the analysis's own words and each fix as a
  button that writes the fixed text back into `$source`. It shows the first eight findings, with
  two fixes each and eight fix buttons in all, and counts the rest, since every button carries
  the whole paste; it reads at most 1 KiB of UTF-8 and says so beyond that; the paste is neither
  logged nor kept.
  `streamlord-analysis` is on the service's classpath for it, and its catalog is named for the
  native image, which CI now proves by calling both routes on the image before it is pushed.
- `POST /assert` on streamlord-live, for the front page's test panel: it puts the events
  `GET /feed` sends for 13 on the wire, reads them back with `streamlord-test`'s
  `datastarEvents`, and runs the two assertions the panel shows, `assertPatchElements(selector
  = "#feed", mode = APPEND, containing = "A new head")` and `assertSignal("heads", 14)`. The one
  signal it reads is `broken`, which makes the second assertion expect 99 instead, so the panel
  can show a failure; the count on the page plays no part. A pass is a green alert in
  `#assertion` that counts the events; a failure is the reader's own `AssertionError` message,
  stream and all, in a red one, with a line under it saying so. `streamlord-test` is on the
  service's classpath for it, and CI calls the route on the image both ways.
- `GET /feed/reset` on streamlord-live, for the front page's Reset button: it patches `#feed` and
  `#wire` empty and `heads` back to 13. A route because no Datastar attribute removes what the
  server appended; an inner patch of nothing is the one way to clear a container.
- The front page's test panel shows the stream it asserts, frame by frame, before the two
  assertions. The frames are written by `:docs-samples:checkDocSamples` into
  `docs/src/generated/feed-13.sse` from the service's own handler and encoder, and CI fails when
  the file is stale, as it does for the `wire=` fences on the pages.

### Changed

- `streamlord-json-jackson` depends on `jackson-databind` 3.2.4 and `streamlord-json-jackson2` on
  2.22.4, both patch releases up from 3.2.3 and 2.22.3.
- streamlord-live's `maxSignalsSize` is 4 KiB, down from the SDK's 1 MiB: the largest store any
  of its pages sends is the judge's paste and a few flags.
- streamlord-live maps the four exceptions the security page names in `StatusPages`, with a
  catch-all under them: signals it cannot read are a `400` and a wrong media type a `415`, each
  with a fixed body, where before the exception's message was the body of a `500`. The catch-all
  answers Ktor's own exceptions with Ktor's own status, so a request Ktor cannot parse is the
  `400` it would be without `StatusPages`; a reader who leaves a stream is not answered and not
  an error.

### Fixed

- `datastarStream` on WebMVC sets `Connection: keep-alive` before it returns, where it was set by
  the stream's own thread with its first write. Spring Boot prints the first MockMvc result of a
  stream while the stream runs, and a header added meanwhile failed the test with
  `ConcurrentModificationException`: about 400 times in 2000 requests under Spring Boot 4.0.8, and
  none since. It never happened outside tests. `Content-Encoding: gzip` still waits for the first
  write, so that a failure before it can be answered with a status, and so still races a test
  that sends `Accept-Encoding: gzip` to a compressing stream, in about every third run; so do the
  headers Spring Security writes when the response commits, unless it is told to write them
  eagerly, and, rarely, every header of a `suspend` controller that suspends before it calls
  `datastarStream`. The testing page says what to do about each. An answer to such a failure that Tomcat closes the connection after, a `400`,
  `413`, `500` or `503` among others, now carries `Connection: keep-alive` beside Tomcat's
  `Connection: close`, and Tomcat closes it.

## [0.13.0] - 2026-10-18

### Added

- `heartbeat` on `respondDatastar` and `datastarStream`, in both variants, and on
  `Streamlord.stream`: a keep-alive interval for one stream, or `null` for none, in place of the
  configured one. It defaults to `Streamlord.heartbeat`, so nothing changes until it is passed.
  One that is not positive throws where it is passed, before the response starts. The functions
  keep their JVM names, and the 0.12 signatures remain, hidden, for code compiled against them.
- `timeout` on `datastarStream`, in both variants: how long Spring lets this stream run, in place
  of `spring.mvc.async.request-timeout` and the container's 30 seconds behind it.
  `Duration.INFINITE` lifts the limit; `null`, the default, leaves Spring's setting alone, and one
  that is not positive throws. It applies to the async cycle that runs the stream, also from a
  `suspend` controller that passes the request, and finds the request through the parameter or
  Spring's `RequestContextHolder`; outside a request that Spring MVC is handling it throws rather
  than doing nothing.
- `heartbeatContext` on `Streamlord.stream`: the context the heartbeat runs in, added to the
  caller's, for an adapter whose block may block its thread. One that carries a `Job` throws.
- `executor` on `datastarStream`, in both variants: the executor that runs this stream, in place
  of Spring MVC's, whose Boot default has eight threads and an unbounded queue. Set on the async
  cycle that runs the stream, also from a `suspend` controller that passes the request; outside
  a request that Spring MVC is handling it throws.
- `SseParser.finish()` in `streamlord-analysis`: the comments that end a stream after its last
  message, handed back when it ends. Both Stream Inspectors call it, so a closing comment shows.
- `Requests.ACCEPT`, `readsAsStream`, `unreadBody`, `NON_STREAM_LIMIT` and `NON_STREAM_CUT` in
  `streamlord-analysis`, for both Stream Inspectors: the `Accept` Datastar's client sends, how it
  decides that a response is a stream, and how much of any other response the inspectors read.

- `Casing.signalName`, the check a name that declares a signal passes: what `Casing.validate`
  admits, and only ASCII letters, digits and `_` in parts joined by `.` or `-`.
- `Casing.withModifiers`: a key with its modifiers after it, refused where the key ends in `_`,
  which the client would read as part of the `__` that starts the modifiers.
- `mergePatchAsClient` in `streamlord-core`: the signal store as Datastar's client reads it after
  a patch, which RFC 7386 is not: `onlyIfMissing` leaf by leaf, an object onto an array by index,
  an array or a string at the top level as keys, a `length` key resizing an array, and a `null`
  inside a stored array read as `""`.
  `DatastarEvents.signals` and both Stream Inspectors fold with it. Each rule was measured against
  the client.
- In `streamlord-analysis`, for both editors: `Requests.requestHeaders`, the headers the client
  sends with the user's replacing its own; `Requests.requestsPathProblem`, what is wrong with a
  configured requests-file path; `RequestsFile.error`, why a requests file could not be read; and
  `AttributeSpec.returnsValue`, from the catalog, for the attributes whose value Datastar reads.
- `JsonWriter.writeExpression`: JSON for a place where Datastar's client reads it as an
  expression, such as `data-signals`, with every `\` written as `\u005c`.

- `SignalNames` in `streamlord-core`: the keys and names Datastar's client cannot hold, with the
  reason (`keyProblem`, `pathProblem`), and `nest`, which nests dotted names the way
  `data-signals:a.b` does and hands a clash to a function of yours.
- `Casing.heldKey` in `streamlord-html`: refuses an attribute key whose signal, as Datastar's
  client reads it after lowercasing, cutting at `__` and applying `__case`, it cannot hold.
- `streamlord-analysis`, for both editors, six diagnostics, described under Fixed:
  `literal-rewritten` and `signal-not-read`, where the client's rewrite of an expression does what
  JavaScript would not; `signal-name`, a signal name the client cannot hold or no `$name`
  reaches; `filter-pattern`, a filter pattern written as a string with flags; `computed-value`,
  a value of `data-computed` that is no function; and `statement-split`, a `;` inside brackets in
  a value that is read.
- `SignalPatchException` in `streamlord-core`, an `IllegalArgumentException` that
  `mergePatchAsClient` throws for a patch the client fails on partway through; its `store` is the
  store as the client keeps it then, with the keys before the failing one applied, and its
  `refused` says Streamlord refuses the patch on purpose, whatever the client does with it, and
  the message then says what the client does. It refuses two kinds: more indices past the end of
  an array than `holeLimit`, and a key `__proto__` anywhere in a patch, inside array values too,
  whatever it holds. Where its value is an object with keys, the client mostly throws at that
  key, measured in Chrome, and the bindings on the page stop redrawing. Otherwise it gives the
  object holding the key a foreign prototype, and then, measured against its source, throws on
  some later keys, replaces the object on a later patch, or with a second `__proto__` under the
  first writes into `String.prototype`. The store carried is the patch cut
  before the key that holds the `__proto__`, which is what the client keeps where it throws on
  that key. A store passed in with a key `__proto__` is refused once a patch reaches the object
  that has it.

- `SignalsMediaTypeException`, `Streamlord.requireJsonContentType` with the same setting on the
  Ktor plugin, and `Streamlord.MAX_SIGNALS_DEPTH`; see Changed, Fixed and Security.

### Changed

- `dataOn` and `dataClass` refuse a name that mixes capitals with a hyphen before a lower-case
  letter, such as `fooBar-baz`. No key gives it back: the `__case.camel` the capitals need turns
  the hyphen into a capital too, so the client listened for, or toggled, `fooBarBaz`.
- `BindModifiers.events` are checked as `dataOn` checks an event, and one with a capital or a `.`
  is refused: the client listened for it in lowercase, or as two events split at the `.`.
- `BindModifiers.prop` is written so that Datastar's own camel-casing gives it back, a leading
  capital included (`URL` as `-u-r-l`), and one it cannot give back is refused: `x2y` came back
  as `x2Y`, `_value` as `Value`, `my_prop` as `myProp`.
- A key or name with a no-break space or another space JavaScript's `\s` has and Java's lacks,
  or with a NUL, is refused like any other white space: the client split a class on it, and the
  HTML parser turned a NUL into U+FFFD.
- A name that ends in `_` is refused by `dataOn`, `dataClass`, `dataSignals`, `dataComputed`,
  `dataBind`, `dataRef`, `dataIndicator`, `dataMatchMedia` and `dataPersist` where a modifier
  follows it: the client cuts the attribute name at the first `__`, so `dataClass("B_", ...)`,
  written `b___case.pascal`, set the class `b`, and `dataSignals("b_", ..., ifMissing = true)`
  declared `$b` and lost its `__ifmissing`. Without a modifier it is written as before.
- A Kotlin `Regex` with a back reference before its group has closed, `(a\1)`, `\1(a)` or
  `(?<n>a\k<n>)`, is refused by `SignalFilter`: JavaScript matches it as empty, and Java fails it
  or, in a repeated group such as `(a|b\1)+`, matches what the group took on the pass before, so
  the filter took signals Kotlin would not. `(?:(a)|b\1)+`, whose reference comes after its group,
  is written as it is and still differs: JavaScript clears the group at each pass and Java keeps
  it, so it matches `ab` in JavaScript only.
- A Kotlin `Regex` with a quantifier that has nothing to repeat, such as `$?`, `^{2}`, `\b*`, a
  `{1}` at the start, after `|` or `(`, on a lookbehind or after an empty `\Q\E`, and the empty
  `(?)`, is refused by `SignalFilter`. Java takes them; JavaScript refuses the literal with
  "Nothing to repeat", which stopped Datastar for the rest of the page.
- A dotted name in the pairs of `patchSignals`, `removeSignals` and `dataSignals` now nests, as
  `data-signals:form.email` does: `patchSignals("form.email" to "x")` patches `$form.email`. It
  used to send the key `"form.email"`, which the client stores as one key that `$form.email`
  does not reach. `removeSignals("form.email")` sends `{"form": {"email": null}}`, and where
  `form` is missing or holds a plain value, the client makes it an empty object; a name under
  another one being removed is left out.
- `patchSignals` throws `DatastarEventValidationException` on a name given twice, or given a
  value and signals under it, where the last value used to win, as `dataSignals` already did. Two
  maps under one name merge.
- A name in the pairs of `patchSignals` and `removeSignals`, and every name the HTML DSL writes,
  may not have an empty part or a part that is a property of `Object.prototype`, as written or
  camel-cased: `patchSignals("constructor" to 1)` and `dataSignals("to-string.name", "1")` throw.
- `dataSignals` refuses `__proto__` with `InvalidSignalNameException`, as it refuses any other
  name, rather than `IllegalArgumentException`.
- `datastarEvents` and `LiveDatastarStream` in `streamlord-test` throw
  `DatastarEventValidationException` on a stream whose signals hold a key the client cannot hold,
  as they do on signals that are not JSON, and `assertSignal` no longer looks for a key holding
  a dot, since none can be read. A dotted key is refused inside an array too, where the client
  would keep it, so that one rule holds everywhere.
- `respondDatastar`, its `Flow` variant and `respondElements`, `respondSignals` and `respondScript`
  on Ktor no longer take a `status`, as on Spring: Datastar's client reads neither a stream nor a
  body with any status but 200. Source that passed one, also positionally as the first argument
  of `respondDatastar`, no longer compiles; code compiled against 0.12 still links.
- `datastarElements`, `datastarSignals`, `datastarScript` and `toResponseEntity` no longer take a
  `status`. Datastar's client reads the body of no status but 200, so any other made the response
  do nothing. Source that passed one no longer compiles; code compiled against 0.12 still links.
- Some events that could be built before now throw `DatastarEventValidationException`, and so does
  an `SseFrame` built by hand: a `retry` that is not a whole number of milliseconds from 1 to
  `Int.MAX_VALUE`, and an `eventId` that is not printable ASCII or has a space at either end. The
  line carries whole milliseconds, so a fraction was cut off, and under one it read `retry: 0`, a
  reconnect with no wait whose backoff never grows; above the limit the browser's timer overflows.
  The id goes back as the `last-event-id` header, and only printable ASCII survives that: `fetch`
  cannot send above U+00FF, Tomcat and Netty answer a control character with 400, Ktor's CIO turns
  Latin-1 into U+FFFD, and edge spaces are stripped.
- `SseDecoder`, `SseReader` and with them `streamlord-test` (`datastarEvents`, `DatastarEvents`,
  `LiveDatastarStream`) read a stream as Datastar's client does rather than as the browser's
  `EventSource`, and `SseDecoder` now reads through `SseReader`, so a text and a stream still
  arriving are read by one parser. A line without a colon is skipped, so a bare `event` or `id`
  keeps the value before it, and a byte order mark before a field or a value is dropped. An `id`
  is kept in `messages()` as written; decoding fails on one that would not come back as written
  as `last-event-id`, by the rule events are built with: printable ASCII, no space at either
  end. A `retry` that is not a whole number of milliseconds from 1 to `Int.MAX_VALUE` fails too.
  Both are checked on every message, Datastar's or not, and on an unfinished one at the end, since
  the client takes them per line; `SseReader.finish()` and `LiveDatastarStream` check that last
  one as well. A data line with no space after its key fails: the client would read the key as
  the line minus its last character. `onlyIfMissing` and `useViewTransition` are true with spaces
  around `true`, as the client trims them.
- On Spring WebMVC the heartbeat runs on `Dispatchers.IO`, beside the block, so a block that
  blocks its thread no longer holds it back. An authorisation's `allows()` and `onRefused`, when
  the heartbeat asks, run there without the request's thread-locals.
- The heartbeat is on by default: `Streamlord.DEFAULT_HEARTBEAT`, 30 seconds, for `Streamlord()`,
  `Streamlord.Default` and the Ktor plugin, and for code compiled against 0.11 or 0.12 that
  relies on the default. Every stream Streamlord opens on Ktor or Spring WebMVC that is silent for
  that long now carries a `: keep-alive` comment, which `DatastarEvents.messages` counts; a
  reader who has gone is found by the comment's failed write, and an idle stream with an
  authorisation is asked again on its schedule. `heartbeat = null` turns it off, on the instance
  or for one stream.
- A stream silent from the start gets its first comment after five seconds, or the interval if
  it is shorter. Netty sends no headers before the first byte of the body and closed such a
  stream after ten seconds. The comment commits the response, so a failure in the block after it
  cuts the stream off rather than answering with a status. On Spring WebMVC, with
  `StreamlordExceptionHandler`, a failure before it still answers with its status; on Ktor the
  status is fixed when the stream starts, as before.
- A test that calls `Streamlord.stream` directly under `runTest`, with a block that waits on a
  real thread, now gets comment after comment while virtual time skips ahead. Give such a test
  `Streamlord(heartbeat = null)`. Ktor's `testApplication` and Spring's `datastarStream` run in
  real time and are not affected.
- A lambda passed inside the parentheses as the last positional argument of `datastarStream` or
  `respondDatastar`, `datastarStream(streamlord, request, authorisation, { … })` or with a
  function reference, now lands on `timeout` or `heartbeat` and no longer compiles. Pass the block
  after the parentheses, as almost all code does, or as `block = …`. Java source written against
  0.12 no longer compiles: javac does not see the hidden signatures, and the new ones take a
  `kotlin.time.Duration`, which Java can only pass as `null`: no timeout change, and for
  `heartbeat` no heartbeat at all, the first comment included. Binaries compiled against 0.12 are
  not affected.
- `dataSignals` in its pairs form reads its keys the way the keyed form reads its name, nested
  maps included: `dataSignals("foo-bar" to 7, "user" to mapOf("first-name" to ""))` declares
  `$fooBar` and `$user.firstName`, the signals `signal("foo-bar")` and `signal("user.first-name")`
  refer to. It declared `foo-bar`, which the helpers could not reach. Two keys that become one
  name throw `InvalidSignalNameException`. The object form and a patch from the server keep their
  keys, and `signal("['foo-bar']")` reaches one.
- A name that declares a signal, in `dataSignals`, `dataComputed`, `dataBind`, `dataRef`,
  `dataIndicator` and `dataMatchMedia`, must be ASCII letters, digits and `_` in parts joined by
  `.` or `-`, since that is all Datastar's client reads after a `$`; `über` or `a:b` declared a
  signal no expression reaches. `signal` and the other expression helpers refuse a name that
  starts with a character the client does not read after `$`. A `/` in any key throws
  `InvalidSignalNameException`, where it reached kotlinx.html and threw its own exception.
- A duration in a modifier or a retry option that the browser's timers cannot honour throws:
  a negative one, which fires at once, and one over `Int.MAX_VALUE` milliseconds, about 24.8
  days, which wraps into a negative one. A flag whose duration is not set throws instead of being
  dropped: `debounceLeading`, `debounceNoTrailing`, `throttleNoLeading`, `throttleTrailing` and
  `leading` on `dataOnInterval`. So do an interval of zero, `half`, `full` and `threshold`
  together on `dataOnIntersect`, of which the client reads one, and a negative `retryMaxCount` or
  `retryScaler`.
- `SignalFilter.regexLiteral` writes the quoting of `Regex.escape`, `\Q...\E`, as escaped text,
  where JavaScript matched the letters `Q` and `E`, writes an octal escape as `\xHH`, and groups a
  character outside the Basic Multilingual Plane so a quantifier takes all of it. It refuses what
  JavaScript reads differently or refuses: possessive and stacked quantifiers, atomic groups,
  `\A`, `\z`, `\Z`, `\G`, `\p{...}`, `\x{...}`, `\b{...}`, `\c` before anything but a capital,
  a back reference to no group or followed by a digit, an escaped surrogate, a character outside
  the Basic Multilingual Plane in a class, the other Java-only escapes, and nested or intersected
  classes. `\Q(?i)\E`, the escape of a text with an inline flag in it, is no longer refused.
  Each was checked against Node.
- `JacksonSignalsCodec` and `Jackson2SignalsCodec` hold three rules whatever the mapper says: a
  `null` for a primitive is refused, also for one with a default and under the Kotlin module's
  `NullIsSameAsDefault`, where Spring Boot's mapper read `{"count":null}` as `0`; a missing Kotlin
  parameter that has no default and cannot be `null` is refused, while a Java creator keeps the
  mapper's rule; and nothing may follow the JSON value. A Kotlin class or interface needs
  `jackson-module-kotlin` on the mapper, and decoding one without it throws
  `SignalsCodecException`, also a class Jackson could build without the module, such as one with
  `var` properties.

- The editors judge an expression as Datastar compiles it. Every `$name.path` outside quotes is
  written as `$['name']['path']` first, so a signal as a parameter, `$items.map($i => $i.name)`, a
  declared name or a property key is the syntax error it is in the browser. The value of
  `data-text`, `data-show`, `data-class`, `data-style`, `data-attr`, `data-signals`,
  `data-computed` and `data-on-signal-patch` is then split into statements on `;` outside quotes
  and regex literals, and the last is wrapped in `return (...)`, so a `;` inside a block or a `//`
  comment at the end is an error too; the others are the body of a function that is not async, so
  `await` at the top level is an error. `&dollar;`, the other named references for ASCII
  punctuation, `&amp` without its semicolon and `&#36` without its semicolon are read as the
  browser reads them.
- The editors flag the pre-1.0 syntax, `data-on-click`, `data-on-load`, `data-bind-value`, which
  1.0 ignores, with the 1.0 form as the fix, and `data-on:click.prevent`, which listens to an
  event named `click.prevent`. A capital in `data-attr:viewBox` is fixed to the object form, which
  keeps the name, where the fix wrote `view-box`; for `data-style:--brandColor` no form keeps it,
  and the message says so.
- The editors read `$id` inside a JavaScript string of an expression, `@post('/x/$id')`, as the
  server value it is, a hint rather than an error with fixes that broke the URL; a `$my-value`
  the page declares with `__case.kebab` is no longer a kebab-case warning; the object form of
  `data-computed` declares its keys; and `dataSignals("foo-bar", "1")`, `dataBind("foo-bar")` and
  the other name-taking helpers declare `$fooBar`, as they write it. `attributes["data-on:click"]
  = "..."`, kotlinx.html's own form, is judged as the attribute it writes, interpolation trap
  included.
- Both Stream Inspectors keep the signal store as the client keeps it, see `mergePatchAsClient`,
  and read `onlyIfMissing` with JavaScript's `trim()`, as the client does, which takes U+FEFF and
  leaves U+001C to U+001F where Kotlin's does the opposite. A patch that fails partway leaves the
  keys before the failing one in the store shown, as the client keeps them, with the reason
  beside it. VS Code's port of the fold answers to the same 366 scenarios as the core's, run
  through the client's own `mergePatch`, so it now turns a `length` into a number as the browser
  does, fails on an object under `length` with `onlyIfMissing` too, and splits a string patch by
  UTF-16 unit. The user's headers replace the client's own, `Content-Type` included, where
  IntelliJ added a second one and VS Code overrode the user's. Copy as curl writes the body with
  `--data-raw`, since with `--data` curl read signals that start with `@` from the file they
  name. Signals written as an object in `inspector.json` are sent as written, where VS Code
  rounded a number past 2^53 and the two editors spelled them differently.
- Both Stream Inspectors refuse to save over a requests file that is not JSON and say why, where
  a save wrote an empty list over it; a byte order mark in `inspector.json` or `env.json` no
  longer hides what they hold, in either editor; and a requests-file path that leaves the
  workspace, which a checked-in setting may carry, falls back to the default and a save says so.
- The IntelliJ inspections take the severity set for them in the profile; by default each issue
  keeps the one the analysis gives it, since a family mixes a syntax error with a hint. The
  inspector shows event names, content types and saved names as text, where Swing rendered one
  that started with `<html>`, images and all. The `data-init="@get(...)"` live template is named
  `data-init-get`, so it no longer hides behind the generated `data-init`.
- In VS Code, "Open Stream Inspector with route" is gone from the command palette, where it threw
  without a route; "Apply recommended colors" reads the user settings alone, so a workspace's
  token rules are no longer copied into them.

- The build and the workflows are pinned: every GitHub Action by commit, with its version in a
  comment Dependabot keeps in step; the Gradle wrapper by `distributionSha256Sum`; the demo's base
  image by digest; the Railway CLI by version; Bun by version both in CI and in the Netlify build
  that deploys the documentation, which now installs from the frozen lockfile and runs Astro under
  the Bun it checked, through `bun x`, as CI does. When Netlify hands that build another Bun, or
  none, it fetches the pinned one from the npm registry, whose integrity hash for that exact version
  npm checks the download against, and stops if it still has the wrong one. Checkouts no longer
  persist the token in `.git/config`, the Railway token reaches only the two steps that deploy, and
  the publishing secrets live in a `release` environment that the three publish jobs and
  `verify-signing` run in, and that takes the release tags, `master` and the release branches. The image is
  pushed by a job of its own, which alone holds `packages: write`, runs on master alone, deploys one
  run at a time and skips a commit that master has already moved past, so an older run never deploys
  over a newer one. The IntelliJ publish job stops when the signing certificate secrets are missing,
  where it published an unsigned plugin under a workflow that looked as if it signed it. The javadoc
  jars carry `META-INF/LICENSE`, as the licence page promises and the other jars do, and every
  published main jar names an `Automatic-Module-Name`.
- `checkDocSamples` fails in CI on a page whose coordinates or wire transcripts it would have
  rewritten, so a release commit that forgets them goes red on the tag; locally it still rewrites
  them. The test-only Tomcat is 11.0.27, past three advisories on 11.0.24. The native image
  passes `-H:+UnlockExperimentalVMOptions` ahead of the experimental options GraalVM will refuse
  without it, and three constructs Gradle 10 removes are gone from the build scripts; the
  warnings left come from the Dokka and GraalVM plugins.
- The Kotlin sources follow ktlint, and CI checks them with ktlint 1.8.0 on every change to a
  Kotlin file or to an `.editorconfig`, so the formatting IntelliJ's ktlint plugin applies on
  save no longer rewrites lines nobody meant to touch. A change to `netlify.toml` alone runs the
  documentation build.
- `SendLatestTest`, which checks that `sendLatest` counts a render that never suspends, ran on the
  wall clock and failed under load. It runs on a clock the test drives, and no longer fails on a
  busy machine.
- `streamlord-analysis`, which only the two editors consume, changed the signatures of
  `ExpressionValidator.validate`, `MarkupValidator.validateAttributes`, `Requests.parseRequestsFile`,
  `MarkupOptions`, `RequestsFile` and `AttributeSpec` with new parameters that have defaults, so
  Kotlin source still compiles against them and code compiled against 0.12 does not link. The
  `signalsTooLarge` and `signalsUnreadable` handlers of `StreamlordExceptionHandler` take the
  exception; the 0.12 signatures remain, hidden.
- `readSignals` and `readSignalsJson`, on Ktor and Spring, refuse signals in a request body that
  is not declared `application/json` with `SignalsMediaTypeException`, which
  `StreamlordExceptionHandler` answers with `415`; Security has the reason. Datastar's client
  declares every body it sends signals in that way, so pages change nothing, but a client of your
  own must set the header. A declared type is checked before the body is read; a body with no
  `Content-Type` is read and refused once it is found not to be blank. Query-string signals are
  not affected. `Streamlord(requireJsonContentType = false)`, or `requireJsonContentType = false`
  in the Ktor plugin, turns the check off. On Ktor, map the exception to `415` in `StatusPages`;
  a block that maps only `SignalsDecodeException` answers it with `400`, since it is one.
  `SignalsDecodeException` is now `open`.
- `Jackson2SignalsCodec` reads through a copy of the mapper handed to it, made once, so that
  mapper's rules and cache are not changed; a mapper of a subclass that does not override
  `copy()` cannot be used.
- `Jackson2SignalsCodec` over a mapper whose default is to skip a `null` in a collection,
  `setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.SKIP))`, now refuses one in a
  Kotlin parameter's `List<Int>` with a `400` where the mapper dropped it, as the Kotlin module's
  own `StrictNullChecks` does on Jackson 2 and 3. `@JsonSetter(contentNulls = Nulls.SKIP)` on the
  parameter still drops it.
- Both editors give an unknown modifier, such as `data-on:click__onse`, an empty `__` or
  `__debounce_150ms`, as a warning rather than an error, and say that Datastar ignores it: the
  1.0.4 client runs the expression without it, measured in Chrome. A modifier written after a dot
  in an event, `data-on:click.prevent`, is an error rather than a warning, since the client then
  listens for an event named `click.prevent` and the expression never runs on a click. The shared
  corpus holds both editors to these severities.
- Both editors give an argument on a modifier that takes none, `__prevent.foo` or
  `__prevent.stop`, and a flag a timing modifier does not know, `__debounce.150ms.foo`, as a
  warning rather than an error, and say that Datastar ignores it: the 1.0.4 client still prevents
  the default or waits the 150 ms and drops the rest, measured in Chrome. With `__prevent.stop` the
  click still reaches the parent, and the warning offers `__prevent__stop`. An argument that
  changes what the client does stays an error: a flag before the duration, a duration with a
  fraction, `__debounce.1.5s`, which the client reads as 1 ms, and `__case.foo` where the default
  case changes the key. On `data-signals`, `data-computed`, `data-bind`, `data-ref` and
  `data-indicator` the client camel-cases a key by default and leaves it as written with a case it
  has no function for, so `data-signals:my-key__case.foo` declares `$my-key`, not `$myKey`. On
  `data-on` and `data-class` the default is kebab, which the client also leaves as written, so
  `__case.foo` changes nothing there and is a warning: `data-on:my-event__case.foo` listens for
  `my-event`, measured in Chrome. A second value after a case the client knows is a warning that
  Datastar ignores it, `__case.camel.foo`, and an error where the client applies it as well,
  `__case.camel.snake`.
- The warning on an unknown modifier in `data-on` says which event the expression listens for. With
  `data-on:my__event` it is `my`: the client cuts the event name at the first `__`, so an event
  named `my__event` never reaches the expression. With `__case` it is the name as the client
  recases it: `my-event` with `__case.kebab`, `myEvent` with `__case.camel`, measured in Chrome.
- In IntelliJ, actions and modifiers in completion keep their order after use: by name, Pro
  actions after the rest, as in VS Code. The platform used to move an item up the list once the
  user had picked it a few times. A test picks items with the platform's usage statistics on and
  checks that the order holds.

### Removed

- `keyName(key, case)` in `streamlord-analysis`, which nothing called and which named a signal
  otherwise than the client does: it took one `__case` and camel-cased a key the client leaves.

### Fixed

- `data-indicator:`, a colon with nothing after it, got the error that Datastar raises
  `KeyOrValueRequired`. The 1.0.4 client raises nothing: it looks for an attribute named
  `indicator:`, finds none and leaves the element alone, measured in Chrome. Both editors now warn
  that an attribute with an empty key does nothing, on every Datastar attribute (`data-on:` was an
  error that it needs a key, `data-text:` one that it takes none, and `data-signals:="{a: 1}"` got
  nothing), and offer to drop the colon where the attribute then runs: with a value, or on an
  attribute that takes none, such as `data-json-signals:` and `data-ignore:`. Without the colon
  and with no value, `data-text` and `data-attr` raise `ValueRequired` and `data-bind`, `data-ref`
  and `data-indicator` raise `KeyOrValueRequired`, measured in Chrome, so there the warning says
  what is missing besides the colon and offers no fix.
  `data-indicator:="busy"` no longer gets the warning that nothing on the element sends a request.
- `data-indicator` with neither a key nor a value, or with both, got the error Datastar raises and
  also the warning that nothing on the element sends a request. VS Code showed both, IntelliJ hid
  the warning under the error. Both editors now give only the error.
- The Templates page and the roadmap said a template tag the analysis does not know gives no
  findings in expression attributes. Measured in Chrome, none of Qute's `{name}`, Closure's
  `{$name}` and Rocker's `@name` works in an expression: Qute's tags are a `ReferenceError` or a
  `SyntaxError` (a bare `{name}` only seems to work, through `window.name`), `{$name}` is a
  `SyntaxError` where the attribute returns a value, and `@name` is one in every expression
  attribute. The pages now say so, and that the editors hold signal names to a stricter pattern
  than the client. They show the value in an attribute of its own, read with `el.dataset`, where
  the engine escapes it and the attribute is quoted, for the attributes that use it as text, and
  warn against putting the tag in a JavaScript string, `data-text="'{$name}'"`, which runs a
  value such as `x' + (window.__pwn = 1) + '` as code once the browser has undone the engine's
  HTML escaping.
- Both Stream Inspectors say, beside a patch Datastar's client throws on, that the bindings
  already on the page then stop redrawing until it is reloaded, since the client leaves its
  update batch open after the throw, though the signals still change, element patches still land
  and new bindings show the current value. Beside a patch Streamlord refuses on purpose, a
  `__proto__` or more empty slots than `holeLimit`, they say that and why instead.
- The editors read a page's signals as Datastar's client stores them. `signal-kebab` said "no key
  declares" a `$my-key` the page declares with its hyphen, in the object form
  (`{'my-key': 1}`), in the value of `data-bind`, `data-ref` or `data-indicator`, or as
  `data-signals:a-1`, which camel casing leaves alone, and its fix to `$myKey` broke a page that
  worked; it now speaks only when the file does not declare `my-key`. A key in capitals declares
  the lowercase signal the browser hands over (`DATA-SIGNALS:FOO` is `foo`, `data-signals:fooBar`
  is `foobar`), the value of `data-bind` names the signal as written, and a key of a nested
  object is a signal under its parent (`{user: {name: 'x'}}` is `user.name`, not `name`).
- The editors judge an expression by the client's own two patterns, `statementRe` and the
  rewrite (engine.ts:415-416 and 448-449 in 1.0.4), ported as written, with the actions
  included. The text of a template literal that also holds a `${...}` is rewritten by the client,
  so `` `total: $total ${$a}` `` shows `total: $['total'] A`; that, and a `$name` inside a regular
  expression, is now a warning (`literal-rewritten`). A `'` in a comment, a regular expression or
  a template opens a string to the client's pattern, which then leaves a later `$b` as a
  JavaScript variable; that is a warning (`signal-not-read`), as is `$år`, which the client does
  not read because only ASCII follows a `$`. `@get` without its parenthesis, which the client
  does not rewrite, is the syntax error it is in the browser, where `@` used to become `_`.
  `new.target` is accepted, since the client compiles the body of a function.
- The editors flag signal names the client cannot hold or no `$name` reaches (`signal-name`):
  `__proto__` anywhere in the name, and `constructor` or another property of `Object.prototype`
  with more of the name under it, which writes into every object of the page, also once the
  client has read the key (`data-signals:_-proto-_.x__case.snake` is `__proto__.x`); an object
  under such a name, in the object form or as the value of `data-signals:constructor`, also
  through parentheses, a conditional or `||` (`({x: 1})`, `$c ? {x: 1} : 0`); a `$` in
  `data-bind="$user"`, which makes the signal `$user` that `$user` does not reach; and a letter
  beyond ASCII or a space, where the message names the bracket form that does reach it,
  `$['my key']`, with a quoted key as one part (`$['a.b']['c d']`). Such a name as the last part
  with a plain value under it, `data-signals:constructor="1"` or `data-bind="toString"`, is held
  as its own key and is not flagged. A key with template syntax in it, `data-signals:${key}`,
  `data-signals:{{ name }}` or, in Kotlin, `attributes["data-signals:$key"]`, declares no signal
  and is not judged. An object spread into the object form, `{a: 1, ...{b: 2}}`, declares its
  keys, and a spread of anything else, `{...init}`, may declare any signal, so the file then
  calls no `$name` unknown.
- The editors check the filters of `data-json-signals` and `data-on-signal-patch-filter`, which
  the client evaluates without signals: a regular expression that does not compile is an error,
  a `$name` there is a warning, and so is a pattern written as a string with flags after its
  last slash (`filter-pattern`): the client cuts one `/` from each end of the string, so
  `'/^user/'` works and `'/^user/i'` matches the text `/i`; the fix is the literal `/^user/i`,
  with a `/` inside the pattern escaped, and flags given twice or `u` with `v` get no fix. The
  `filterSignals` of an action, which the client reads the same way, is checked too. A key that
  only ends in `include`, a key in a comment, and a tail with `d` in it, as in the path
  `'/users/id'`, are not read as a pattern with flags; a template literal without `${` is.
  `data-on-signal-patch:filter`, a key 1.0.4 accepts, is no longer an error; the catalog lists it
  under `keys`, read as `AttributeSpec.keys` in `streamlord-analysis`. `data-computed` without a
  key warns when its value is sure to be no object of functions, and errs where the client raises
  `ComputedExpectedFunction` for a value sure to be no function: a literal, a unary operation or
  an arithmetic one, not a name, a call or a `?:`, which may give one; an array only with an
  item sure to be no function, since the client takes the functions out of an array. A `;`
  inside brackets in a value that is read, which the client splits all the same, losing the
  value, is a warning.
- The editors explain a duration after a flag, `__debounce.leading.500ms`, in one message with
  the reordered modifier as the fix, where they gave two, and a fraction, `__debounce.1.5s`,
  which the client reads as 1 ms, with `__debounce.1500ms` as the fix; with a flag the client
  does not know after them, that flag is named and no fix is offered. The reordered modifier is
  named only when every other argument is a flag the client knows, so `__debounce.leading.1.5s`
  is not told to become `__debounce.1.leading.5s`. The advice for
  `data-style:backgroundColor` is the kebab-case property, with the fix, where "name the
  property in lowercase" led to a property that does not exist.
- The editors judge the expression of `dataOnSignalPatch("...")` as `data-on-signal-patch`, whose
  value the client returns, so `if ($a) $b = 1` is the syntax error it is in the browser; it was
  judged as the filter the helper also writes, and the hover and the documentation showed the
  filter. `Catalog.attributesByKotlin` in `streamlord-analysis` maps a helper to the attribute
  its string fills.
- Template syntax in the key of any attribute, `data-on:${evt}`, `data-class:{{ c }}`,
  `data-on:<%= evt %>`, or a Kotlin template in the key of `attributes["data-on:$evt"]`, leaves
  the key, its modifiers and the value that went with it unjudged until the page is rendered,
  where the editors reported a missing value, a missing key or an unknown modifier on pages that
  work. `MarkupValidator.validateAttributes` in `streamlord-analysis` takes `kotlin` for names
  written in Kotlin source.
- The two editors read the same signals in an expression: IntelliJ now reads a signal in the code
  of a template literal, and VS Code no longer reads one inside a regular expression; a signal in
  a comment is read by neither. Neither editor offers `patch` outside `data-on-signal-patch` and
  `dataOnSignalPatch`, where it is not defined.
- The IntelliJ plugin no longer sets colours for the Default and Darcula schemes, which won over
  every theme built on them. Each Datastar token takes the colour the theme gives a standard key.
  IntelliJ's own schemes give a function call and a class name no colour, so there an action
  other than a backend one, an attribute key and a modifier argument read as plain text.
- The VS Code package carries acorn's licence, in `ThirdPartyNotices.txt`, beside the bundle that
  includes acorn.
- `mergePatchAsClient` now folds a patch exactly as the 1.0.4 client does, held to the client's
  `mergePatch` built from source over 329 scenarios: a `length` is turned into a number the
  JavaScript way (`[]` and `false` are 0, `"2"` is 2), deleting a `length` throws, a patch that
  throws keeps the keys before it, and keys are walked and kept in JavaScript's order, also in
  objects inside arrays and in a store that a number, a boolean or `null` hands back unchanged. A
  fold copies each object and array on the patch's path in full width and hands the rest back as
  the same values, so a small patch into a large store costs time linear in the width of the
  levels the patch goes through, not in the size of the store.
- `SseDecoder` read some frames the client takes as malformed. An empty
  `viewTransitionSelector` is none; an element patch with no elements, or only white space as
  HTML counts it (tab, line feed, form feed, carriage return, space), in a mode that needs them
  decodes to what the client does with it: an `outer` or `replace` with a selector takes the
  target out and leaves the white space in its place as a text node, and comes back as a removal;
  an `inner` puts the white space in place of the target's children; an `append`, `prepend`,
  `before` or `after` inserts it as a text node and changes no element, and is no event, as is an
  `outer` or `replace` without a selector whose elements hold no tag. A no-break space, U+3000,
  U+001C or any other character is text the client inserts, measured in Chrome, and comes back as
  elements; `PatchElements` and `ElementsResponse` take such elements too, where Kotlin's
  `isBlank()` refused them, except in an `outer` or `replace` without a selector, where the client
  takes only elements and they would patch nothing. A frame that changes no element but asks for
  `useViewTransition` is no event, though the client starts a view transition around it. Flags are read with JavaScript's `trim()`, so `true` followed by a
  byte order mark is true and `true` followed by U+001C is not.
- A string with half of a surrogate pair reached the client as `?` from a codec, in elements and
  in a script response. Signals now carry it as `\ud800`, as the built-in writer does, and
  elements as U+FFFD; so does a script from `respondScript` or `datastarScript`, with its
  attributes, as `executeScript` already did in a stream.
- `dataClass` on a class that mixes capitals with a hyphen before a lower-case letter, such as
  `MuiButton-root`, says to use the object form, `dataClass("{'MuiButton-root': ...}")`, which
  keeps it as written; "write it in one case" could not be followed for a class from a library.
- On Spring with Tomcat, a stream answered a request that sent `Connection: keep-alive` with
  `Connection: keep-alive, keep-alive`: Tomcat adds its own to such a request. The stream now
  leaves the header to Tomcat then, and still sets none over HTTP/2 or when the request asks to
  close.
- On Ktor, reading the signals of a body that was already read threw Ktor's own
  `RequestAlreadyConsumedException`; it now says, as on Spring, that the body was read before.
- The documentation of `maxSignalsSize` and the security page said the 1 MiB cap holds for the
  `datastar` query parameter. The server refuses the request line at 4 to 8 KiB, with a status
  Datastar's client does not retry; the page now gives the numbers per server and points large
  signals to `@post`.
- The security page now says that Ktor 3.6 without `StatusPages` sends an exception's message in
  the 500, and shows a catch-all that does not.
- KDoc: `timeout` on `datastarStream` said Spring cuts the stream, where Tomcat and Jetty end it
  cleanly and the client does not reconnect; `dataIndicator` counts only fetches from its own
  element; `FetchOptions` writes an option set to its default value; `SignalFilter` names the
  differences at NEL and with `IGNORE_CASE`, where Kotlin folds the Kelvin sign into `k` and the
  long s into `s` and JavaScript does not.
- A signal patch could carry a key Datastar's client cannot hold, from pairs, a codec or a JSON
  string, at any depth. `__proto__` with some objects under it, `{"x": 1}` among them, made its
  merge throw halfway through a batch it never closed, so the bindings already on the page
  stopped redrawing until it was reloaded. An object under a property of
  `Object.prototype` was written into the built-in one before every request:
  `{"constructor": {"prototype": {"x": 1}}}` set `x` on `Object.prototype` for every object on
  the page, and `{"toString": {"name": 1}}` made every later request fail. A dotted key was
  stored as one key that `$a.b` does not reach, and dropped from the client's requests.
  `PatchSignals` and `SignalsResponse` now throw `DatastarEventValidationException` on such a
  key; a plain value under `constructor` or `toString` goes through.
- `data-signals:constructor.prototype.x`, `data-signals:to-string.name` and the same names in
  `dataBind`, `dataRef`, `dataIndicator`, `dataComputed` and Pro `dataMatchMedia` set a property
  on `Object.prototype` or broke every later request in the client. Such a name is now refused,
  also where the client reads it differently from how it is written: `"Constructor"` with
  `Case.KEBAB` is read as `constructor`, `"_-proto-_"` with `Case.SNAKE` as `__proto__`, and
  `"constructor_"` with `ifMissing` as `constructor`, since the client cuts the key at the first
  `__`.
- A value ending in `\` emptied the attribute it was written into. Datastar's client cuts an
  expression into statements at each `;` outside a string and reads a quote after a `\` as
  escaped, so `dataSignals("path" to "C:\\", "sep" to ";")` declared no signal at all, and
  `js("C:\\")` followed by a `;` in `data-text` showed nothing. `dataSignals` now writes `\` as
  `\u005c`, through the new `JsonWriter.writeExpression`, and the HTML helpers' literals write it
  as `\x5c`.
- The literals the HTML helpers write (`js()`, `set()`, `setAll()`, the fetch options and the Pro
  actions) left `"` and `&` as they were. Pasted between the double quotes of an attribute in a
  string or a template, as the HTML DSL page says they can be, a `"` in a value ended the
  attribute, which let a value such as `"><img src=x onerror=…>` run script, and `&lt;` reached
  the client as `<`. Quotes, `&` and `\` are now written as `\xHH`, so `'it\'s'` is now
  `'it\x27s'`.
- A `'` in a regex filter opened what Datastar's client took for a string, so a signal after the
  filter in the same expression was left as a global variable: `@setAll(true, {include: /it's/});
  $msg = 'done'` set `window.$msg`. A `"` broke the attribute in a string, and the client rewrote
  `$name` and `@name(` in one as a signal and an action, so `/^[_$a-z]+$/` matched no signal
  name. Quotes,
  backticks, `&`, `\`, `$` and `@` in a regex filter are now written as `\xHH`, and an anchoring
  `$` before a word character as `$(?:)`.
- `JsLiteral` wrote a map key such as `$id` bare, which the client rewrote as a signal, and the
  whole expression failed to compile. Such a key is now quoted.
- Documentation corrected where the audit found it wrong: the README's MIT condition names the
  licence text as well as the notice and links one documentation domain; the security page's
  ordered-delivery prose names the raid the transcript shows and says the JSON parser is run
  against a fixed seed, not fuzzed afresh; the CSP note no longer says Spring Security's writer
  can agree with the nonce; the WebFlux page says Spring writes no space after any colon; the
  roadmap's release counts are gone; and the editor READMEs link a page that exists on `master`.
  The site's link check now covers same-page anchors.
- `Jackson2SignalsCodec.default()`, and any Jackson 2 mapper such as Spring Boot's, read a missing
  or `null` primitive as `0`: `{}` into `data class Delete(val id: Long)` reached the handler as
  `Delete(id=0)`. It is refused.
- `JacksonSignalsCodec` over a mapper without `jackson-module-kotlin` built a Kotlin class from
  its defaults and dropped what the browser sent: `{"page":3}` into `page: Int = 1` gave `1`.
- `SignalFilter.regexLiteral` escapes U+2028 and U+2029, which ended the literal and made the
  client throw a SyntaxError.
- The Pro attributes wrote a duration under a millisecond as `0ms`, where the free ones refuse
  it; they follow the free attributes' rules now.
- `dataStyle("--brandColor", ...)` wrote `--brand-color`, another custom property. A custom
  property with capitals is refused, since Datastar cannot set one in either form: the browser
  lowercases the key, and the object form turns each capital into a hyphen.
- `collectSignals` and `collectDeclaredSignals` in `streamlord-analysis`, and the VS Code
  extension, read the top-level key of `dataSignals("foo-bar" to 1)` as declaring `$fooBar`, as
  the DSL now writes it. A key with a hyphen in the pairs form was not collected at all. Keys of a
  nested map are still offered as names of their own, as before.
- The optimistic-UI example on the components page threw when it rendered: it set an attribute
  on the `li` after its button. The documentation's `sample=html` examples now run as part of the
  build as well as compile.
- `SseFrame(id = "")` builds again: an empty id is valid SSE and drops the client's
  `last-event-id`, and 0.12 took it. An event still refuses one.
- `SseDecoder`, and with it `datastarEvents`, fails on a data line whose key `Object.prototype`
  has, `constructor` or `__proto__` among them, which makes Datastar's client throw and reconnect
  instead of patching; and it reads an empty `selector` as none, as the client does.
- An inner patch of nothing is allowed, the one way to empty a container, and an
  `ElementsResponse` for an inner or remove patch needs no body.
- A non-SSE response refuses a selector that is not printable ASCII, which reached the client
  garbled or lost as a header, and writes its script attributes with `\u` escapes, which
  `JSON.parse` restores.
- `JsonWriter` escapes a surrogate without its other half, which arrived as a replacement
  character, and `JsonNumber("-0")` keeps its sign.
- `ExpressionGuard` catches what the Dollar trap page said it does: a residue of only `;`, a
  statement that starts with an operator after a `;`, keyword operators with a side missing
  (`in`, `typeof`, `void` and the like), a leading binary `+` or `-`, and an empty `${}`. It reads
  strings, regex literals and comments left to right, so `/'/.test($s)` no longer fails.
- `ElementsGuard` decodes the references the browser decodes: the named ones for the ASCII an
  expression is made of, `&plus;` among them, and numeric ones without their semicolon.
- `redirect` refuses a URL with a scheme that does more than navigate, such as `javascript:`,
  which the browser would run on the page; `ExecuteScript` refuses an effect attribute beside
  `autoRemove`, which the browser dropped along with the removal; and its KDoc and the CSP nonce's
  say what the client does with a nonce and which attribute the aliased bundle reads.
- A `CancellationException` thrown by an authorisation's `allows()`, such as a `withTimeout` around
  a database call, ended only the coroutine that asked. When the heartbeat asked, that was the
  heartbeat, which stopped without a word, and an idle stream was never asked again nor found a
  reader who left. It now ends the stream as an `IllegalStateException`, on both paths.
- On Ktor, a block that throws has its frames delivered and its stream cut. With
  `heartbeat = null`, CIO lost the last frame in about two failures of five and sent no bytes at
  all in most failures before a first write, some of which then hung open; Netty ended half of
  them as if they had finished, which the client does not retry. Ktor gives a writer no way to wait
  for the engine, so a failure now waits a moment, at most a second for a stalled reader, before it
  is thrown on; over 200 failures of each kind on each engine, none lost a frame or ended cleanly.
  Ktor's Tomcat and Jetty engines ended a failed body with its last chunk, as if it had finished,
  gzipped or not, so the client did not reconnect and the page stopped updating without a word.
  Streamlord now cuts the connection there too, on Tomcat 10.1 and 11 and Jetty 11 and 12, over
  HTTP/1.1 and HTTP/2, where only the stream is reset, and on Tomcat 9 under Ktor's javax engine
  over HTTP/1.1. It does so by reflection, with no new dependency, and on Tomcat only once Ktor
  has completed the request, and not on a connector set to keep its facades
  (`discardFacades = false`). On an engine Streamlord does not know, the stream ends as the engine ends it.
- On Ktor's servlet engine, jakarta and javax alike, `respondDatastar` throws a stream's failure,
  whatever was thrown, only once the engine's response writer has finished, and a cancelled call
  waits for that writer before the cancellation goes on. The frames still get at most a second for
  a reader who stopped reading, after which the writer is stopped where Streamlord can tell it is
  Ktor's, and the cut is as before. The Ktor page shows how to end a `respondBytesWriter` of your
  own normally when it fails.
- `respondDatastar` on a call already answered throws `IllegalStateException`. It did nothing on
  CIO and threw Netty's own exception from the header loop.
- Neither Ktor nor Spring WebMVC sends `Connection: keep-alive` to a request that asked to close.
- `streamlord-test` folds a signal patch onto an array as Datastar's client does: an index past
  the end extends it, and `null` leaves an empty slot, read as `null`. It dropped both.
- `LiveDatastarStream` sends the client's `Accept`, now `DatastarProtocol.ACCEPT`, and words a
  frame it fails on as one Streamlord's rules refuse: the client takes `retry: 0` and `1e3`.
- The pages give the measured disconnect figures (four to five heartbeats on CIO, two on Netty),
  say which engine sends its headers when, and what Netty does with `heartbeat = null`.
- The core, `streamlord-test` and the Ktor tests also run on the newest coroutines release, and
  `HttpRequestLifecycle` is tested on Netty.
- On Spring WebMVC, a failure inside a stream that has started is cut off, as the documentation
  said, also when `StreamlordExceptionHandler` handles it: the handler throws the exception on
  when the response is committed. It used to answer, which ended the chunked body as if the stream
  had finished, so Datastar's client neither retried nor reported it, and a body an advice
  returned was appended to the event stream.
- `datastarStream` sets `Content-Encoding: gzip` and `Connection: keep-alive`, and starts the gzip
  member, with the stream's first write, so a failure before it, a `500` or a mapped `413` or
  `503`, is no longer answered as gzip over a plain body.
- A `ShallowEtagHeaderFilter`, or any `ContentCachingResponseWrapper`, no longer holds a stream back
  until it ends: the stream flushes the response underneath it.
- On Jetty, a stream is no longer sent one byte per chunk. Its buffer was set to none, which Jetty
  reads as one byte; it keeps the size it has.
- A stream on a response that something wrote to without ending its line, which lost its first
  event to that line, writes a blank line first. This also comes after a change in this release:
  0.12 failed on such a response, with a 500.
- `datastarStream` on a response whose writer is taken throws `IllegalStateException` where it is
  called, not later on Spring's async thread.
- A `suspend` controller that resumed on the thread of another request, and passed `timeout` or
  `executor` without the request, set them on that other request. It now throws, as it does with
  no request at all.
- `readSignals` and its siblings on a request whose declared body was already read, by a
  `@RequestBody`, a form or multipart filter, or a second read, throw `IllegalStateException`
  rather than read no signals and return the default. `maxSignalsSize = Int.MAX_VALUE` no longer
  fails with an overflow.
- `interpolate` and `ElementsGuard` read markup as the browser's tokenizer does. A value got past
  `interpolate`, and a `$`-trap residue past the guard, where the scanner read the markup
  otherwise: a comment closed by `<!-->` or `--!>`, a bogus comment such as `<!x …>`, a
  non-ASCII letter after `<`, `</titlex>` taken to end a `title`, the raw text elements it did not
  know (`xmp`, `iframe`, `noembed`, `noframes`, `noscript`, `plaintext`), and a `title` or a
  `textarea` inside `<svg>`, which holds elements there. `interpolate` reads the markup both as
  HTML and as the inside of an `<svg>` or `<math>` and holds a value to both; the guard reads it as
  svg where the markup opens one or the patch carries an SVG or MathML namespace.
- `interpolate` judges a URL attribute whole, every value in place, so `href="%s%s"` with `"java"`
  and `"script:alert(1)"` throws, as does a placeholder in `href="javascript:…(%s)"`, which used to
  be escaped and admitted. `values`, `to` and `from` on SVG's animation elements are URL attributes,
  and custom prefixes are tried longest first, as the guard already did.
- `PatchSignals` and `SignalsResponse` refuse signals that are not one JSON object, and `JsonNumber`
  text that is not a JSON number, with `DatastarEventValidationException` and
  `IllegalArgumentException`. Datastar's client runs signals that `JSON.parse` refuses as
  JavaScript, and `JsonWriter` wrote a `JsonNumber` as it was given. An array or a string, which
  arrived as an object keyed `0`, `1` and on, is refused too. `SseDecoder`, and with it `datastarEvents` and
  `LiveDatastarStream`, fails on such signals where it reads them, since the client would run
  them.
- A script body escapes `</body>`, `</head>` and `</html>`, which made Datastar's client read the
  patch as a whole document and drop the script or append a second `html` element, and a
  `<script` after `<!--`, which kept the element open past its end tag. `JsonWriter` and
  `JsLiteral` escape `<!` in strings for the same reason, so `redirect("/q?<!--<script>")` runs.
- `JsLiteral` writes a `__proto__` key as a computed key, the only form that is a key in an object
  literal rather than the prototype, and `dataSignals` refuses `__proto__`, which Datastar's
  client never creates.
- The Security page no longer says `data-ignore` contains raw user HTML: the user's `</div>`
  closes the wrapper, and a patched `<script>` runs inside it. User HTML has to be sanitized
  first.
- `streamlord-ktor` runs on every Ktor 3. Ktor's reified helpers inlined a `TypeInfo`
  constructor that only Ktor 3.6.0 has, so on Ktor 3.0 to 3.5 every `respondDatastar`, the plugin
  and `cspNonce` failed with `NoSuchMethodError`. Its tests now also run, compiled against Ktor
  3.0.0, on the adapter as it is published.
- Every module compiles in a project on Kotlin 2.2, which Spring Boot 4.0 manages. They carried
  Kotlin 2.4 metadata, which a Kotlin 2.2 compiler refuses to read. They are built at language and
  API level 2.2 and name the 2.2 standard library in their POMs, and the documentation's examples
  are compiled with Kotlin 2.2.21 on every build.
- `datastarStream` without a request no longer sends `Connection: keep-alive` over HTTP/2, which
  curl and Node refuse; Tomcat passes the header through. It finds the request Spring MVC is
  handling for the response when none is passed, and sets the header only for HTTP/1.1. With no
  request found, outside Spring MVC or in a `suspend` controller after it suspended, it leaves the
  header out; HTTP/1.1 keeps the connection open without it.
- `datastarStream` on a response something has already written to, but not committed, no longer
  fails with a 500: `prepareForSse` asked for no buffer, which Tomcat, Jetty and Undertow refuse
  once anything is written, and Streamlord now does without it, since every event is flushed
  anyway. Such a stream is not gzipped, since the bytes before it would break the gzip member. A
  committed response, whose headers have gone out, fails with a clear `IllegalStateException`
  rather than the container's.
- The catalog notes that the `responseOverrides` fetch option has no effect in Datastar 1.0.4,
  whose client never reads it, as the DSL's KDoc already did.
- The Stream Inspector, in IntelliJ and VS Code, asks and reads as Datastar's client does. It
  sends the client's `Accept` (`text/event-stream, text/html, application/json`), in the request
  and in the curl line, so a server that answers by `Accept` answers the inspector as it answers
  the page. It reads a 200 that is not HTML, JSON or JavaScript as a stream, a missing
  `Content-Type` included, and shows any other response without a stream, any status but 200
  among them, read up to 1 MiB. A stream with any status but 200 is shown at once with its body
  unread, as the client leaves it, rather than waiting on a body that may never end. IntelliJ
  reads every `Content-Type` value, as the client does, where it read the first. The hint under
  an error without a body no longer blames an `Accept` the inspector no longer sends.
- The Stream Inspector, in IntelliJ and VS Code, reads a stream as Datastar's client does. The
  analysis `SseParser` now reads through the core's `SseReader`, and the VS Code parser is ported
  to the same rules, held to one shared corpus of cases. A message carrying only a `retry` is
  shown; a line without a colon is skipped; a byte order mark before a field or a value is
  dropped; a data line without a space is split as the client splits it; a `retry` past a 32-bit
  int is shown as none in both, where VS Code kept the previous one; an explicit `event: message`
  is shown; a message with comments and an `id` or a `retry` is shown as a frame rather than a
  keep-alive; data lines are joined as the client joins them before they are split, so empty
  ones before the first with text are gone; and a long line in small pieces is read in linear
  time, where it was quadratic. The inspector judges nothing, so a `retry` its last line cannot
  give as a whole number that fits an int shows none, where the client may keep an earlier one or
  read `1e3` as 1000.
- `SseReader.finish()` resets the reader before it rejects an unfinished message, so a reader
  that threw reads on cleanly.
- `SseFrame`, a public data class, validates what it is given: a line break in its `event`, `id`
  or a data line, or a NUL in its `id`, could write a line of its own into the stream, an event or
  data the server never meant to send. A NUL in data is still written, since markup can carry one.
  This reverses 0.12.0, where `SseDecoder` dropped an `id` with a NUL in it and read on: the id is
  now kept as written, and decoding fails on it.
- Signals read into a type are refused with `SignalsDecodeException`, a `400`, when objects and
  arrays nest more than `Streamlord.MAX_SIGNALS_DEPTH`, 64, deep, before any codec reads them. It
  is the depth the built-in parser and writer already kept. `KotlinxSignalsCodec` overflowed its
  stack on twenty kilobytes of `[` into a `JsonElement`, and the `StackOverflowError` went past
  every handler: real Ktor CIO answered `500`. The codec also turns that error into
  `SignalsDecodeException` for a caller that decodes a body itself, as on WebFlux. Through
  `readSignals`, the Jackson codecs, which allowed 1000 and 500 levels, stop at 64 as well.
- `Jackson2SignalsCodec` refuses a `null` inside the `List<Int>`, `Set`, `Array` or
  `Map<String, Int>` of a Kotlin constructor or `@JsonCreator` parameter, at any depth, as
  `StrictNullChecks` in the Kotlin module would, with `default()` and with a mapper of your own,
  also in a class with a value class parameter; it reached the handler as a
  `NullPointerException`. A parameter's `List<T>` is left alone, since what `T` stands for is
  known only to the type read, so `Page<Int?>` takes a `null` and `Page<Int>` does too. A `var`
  in the class body, a value class holding a list and a Java class's collections still take
  `null`. Both Jackson codecs refuse a `null` inside a `List<Int>`, `Array<String>` or
  `Map<String, List<Int>>` read whole, at any depth, which the Kotlin module does not check. An
  element type declared nullable, `List<Int?>`, still takes one. The Signals page lists where
  each codec refuses a `null` and where it does not.
- `KotlinxSignalsCodec` threw `ClassCastException` past every handler on any body read into an
  `Array<Int>`, or another array of a primitive type looked up by type, since kotlinx.serialization
  builds an `int[]` for it. It now throws `SignalsCodecException`, a `500`, that says to declare
  it `IntArray` or `List<Int>`. A property of that type in a class is read as before.
- Both Jackson codecs ignore keys the type does not name whatever the mapper says, as the docs
  promised. `Jackson2SignalsCodec(jacksonObjectMapper())`, and any mapper with
  `FAIL_ON_UNKNOWN_PROPERTIES` on, refused every request that carried a signal the type did not
  name.
- `KotlinxSignalsCodec(requireNamedSerializers = true)` let a container of a named type, such as
  `List<Signals>`, through, although the list is looked up whole and that lookup reads the class
  of what it holds, the reflective path a native image cannot take. It is now refused until the
  container is named itself, as `typeOf<List<Signals>>() to ListSerializer(Signals.serializer())`.
- The Spring pages give the real symptom of a handler that takes `Streamlord` as a parameter: every
  request answers `500` with `BeanInstantiationException`, on WebMVC and WebFlux, rather than
  running on a default instance. The security page says why the writer escapes U+2028 when current
  browsers accept it raw, and that it escapes `<!` as well as `</`, and the introduction and the
  README that a request asking to close gets `Connection: keep-alive` from the version written by
  hand on CIO, Netty and Tomcat, and not from `respondDatastar`. The roadmap no longer says what a
  named release lacks and describes the store panel with the merge it uses, `mergePatchAsClient`.
  The install page draws the codec dependency trees as lists, not as text art; on a phone a
  coordinate breaks at a dot, at its colon and before its version, and at a hyphen in its name only
  on a line narrower than the name. Inline code written in Markdown breaks after a `.`, `::`, `/` or
  `(` before it breaks inside a name. The editor READMEs list the IntelliJ inspection for a signal
  no file defines, the Stream Inspector's two settings and the required signing secrets, and VS
  Code's `test:live` runs against the repository's own demo rather than a service outside it.

### Security

- Signals in a request body are read only when it is declared `application/json`. A
  `<form enctype="text/plain">` on another site can post JSON-shaped text with the visitor's
  cookies and without a CORS preflight, and `readSignals` read it as signals, measured in Chrome on
  Ktor CIO and Spring Tomcat. It is now refused with `SignalsMediaTypeException`; Changed has what
  that asks of a client of your own.
- The install page says that Ktor 3.0's Netty engine brings Netty 4.1.114.Final, which has known
  vulnerabilities, and shows how to raise Netty in Gradle and Maven or move to a newer Ktor. The
  adapter's tests on Ktor 3.0 run on the raised Netty, 4.1.139.Final, rather than on 4.1.114.

## [0.12.0] - 2026-10-11

### Added

- `streamlord-analysis`, for both editors: `indicator-without-action`, a warning on a
  `data-indicator` whose element sends no request, since the indicator only tracks its own
  element's; and quick fixes for two modifiers that parse as something else,
  `__debounce_150ms` to `__debounce.150ms` and `__prevent.stop` to `__prevent__stop`.
  `validateAttributes` takes the tag's text as well, so that a template construct inside the tag
  is seen.
- `sendLatest(states, minInterval, resumeFrom) { render }` on a stream: renders the newest state
  and sends it, at most once per interval, and not at all when nothing changed. Each render's
  fingerprint is its event id, so a reconnect that passes `last-event-id` back as `resumeFrom` is
  not sent what it already shows. `SseEncoder.fingerprint` computes it. Documentation: the Live
  views page covers commands and the view, the element that opens the stream, and
  `requestCancellation`.
- `Streamlord(compress = true)` and `compress` on the Ktor plugin: gzip on Ktor and Spring WebMVC
  streams whose request takes it, flushed event by event so nothing waits, with the window kept
  across events so a repeated render costs little. WebFlux is not covered. With `compress` on,
  every stream varies on `Accept-Encoding`, gzipped or not. `DatastarProtocol.acceptsGzip` reads
  an `Accept-Encoding` header for the adapters, and takes a weight only as RFC 9110 writes it.
- `replaceUrl(url)` and `pushUrl(url)` on a stream: the server puts the URL of the state it
  rendered in the address bar, replacing the current history entry or adding one, without a
  reload. The URL is quoted as `redirect` quotes it; pass a path on the page's own origin.
  Documentation: a Live views page, which covers them.
- `streamlord-test`: `LiveDatastarStream`, a stream held open and read as it arrives, for a test
  that sends a command and waits for the patch it causes on the stream that was already there.
  `awaitPatchElements`, `awaitSignal` and `next` wait on real time, also inside `runTest`, and fail
  with the stream they read. `open(uri)` uses the JDK's HTTP client and `of(reader)` any other.
  Coroutine API, for Kotlin tests.
- `streamlord-core`: `SseReader`, which reads a stream that is still arriving and hands back each
  message as its blank line arrives, and `SseDecoder.event` for one message at a time.
  Documentation: a Live views page, which covers them.
- Documentation: a Components and plain JavaScript page (attributes down and events up for
  custom elements, window events from plain scripts, `data-ignore-morph` on both sides of a
  morph, optimistic state rolled back on a failed fetch), and the signals page covers signals that
  stay in the browser, `filterSignals` and `payloadExpr`.
- `Streamlord(heartbeat = ...)` and `heartbeat` on the Ktor plugin: a `: keep-alive` comment on
  every Ktor and Spring WebMVC stream that has written nothing for that long, so an idle proxy
  keeps the connection open. With a `StreamAuthorisation`, an idle stream is asked again on the
  heartbeat's schedule. WebFlux's `asServerSentEvents` is not covered. Off by default.
- `streamlord-analysis`, for both editors: `signalReferencesInKotlin` and `signalReferencesInHtml`, every `$name` a
  Datastar expression reads, with source offsets; `collectSignalDefinitions`, the signals a file
  defines without the ones it only reads, plus every `$name = ...` an expression assigns; and
  `unknownSignalIssue`, the `unknown-signal` warning for a read no definition covers, with the
  nearest defined name as the fix. `isKnownSignal` counts `$user.name` as known when `user` is
  defined, and `$form` when `form.email` is.
- `streamlord-analysis`: `ServerLog`, which reads where a server says it started from its log
  (Spring Boot's Tomcat, Jetty, Undertow and Netty lines in 3.5 and 4.0, Ktor's "Responding at"),
  context path included, and which lines are warnings or errors; `RunningServer`.
  `mergeVariables` takes a running server as `baseUrl` when the env file sets none, as the new
  `VariableSource.RUNNING` with the name it was started under; `baseUrlSuggestion` and
  `withBaseUrl` offer and write its URL when the file's differs.
- `streamlord-spring`: `StreamlordExceptionHandler`, a `@RestControllerAdvice` that answers
  `SignalsTooLargeException` with `413` and `JsonParseException` and `SignalsDecodeException` with
  `400`, instead of the `500` they are unmapped. It is not registered for you:
  `@Import(StreamlordExceptionHandler::class)`. It answers with no body, has the lowest
  precedence, and works for WebMVC and WebFlux annotated controllers.
- `streamlord-analysis`: `Issue.position(source)` gives the line and column an issue starts at,
  and `Issue.format(file, source)` writes it as `file:line:column: severity: message [code]`, the
  form IDEs and CI make clickable. `sourcePosition` and `SourcePosition` do the same for any
  offset.

### Changed

- A codec that cannot read a request's signals throws `SignalsDecodeException`, a new subclass of
  `SignalsCodecException`, so a `catch` of the old type still catches it. The kotlinx and Jackson
  codecs do, and so does `readSignals` for a failure it wraps; the built-in reader still throws
  `JsonParseException`. A `SignalsCodecException` of any other kind, a value JSON cannot hold on
  the way out or a type no codec is set up for, is the server's fault: map
  `SignalsDecodeException` to `400` and leave the rest a `500`.
- `StreamAuthorisation.onRefused` runs to its end even while the handler is being cancelled, so
  a frame under way is finished rather than cut, and is given
  `StreamAuthorisation.LAST_WORDS_LIMIT`, five seconds, and no longer; so is the end of a gzipped
  Ktor stream after it. A write that blocks its thread, as a servlet's does, cannot be cut.
- `SseDecoder` ignores an `id:` with a NUL in it, as the SSE spec and the browser do.
- `streamlord-analysis`, for both editors: signal collection, for completion, `signalDrift` and
  the new check alike, honours `__case` on a keyed attribute (`data-signals:my-value__case.snake`
  is `my_value`, `__case.pascal` gives `MyValue`) and reads the keys of JSON text handed to
  `patchSignals`, as in `patchSignals("""{"count": 1}""")`. `keyName(key, case)` names the
  signal a key declares.
- `streamlord-analysis`, for both editors: `Route` carries the `query` parameters and `headers`
  its handler reads, as `RouteParam`s that say whether they are required: Spring's
  `@RequestParam` and `@RequestHeader` from the parameter list, and Ktor's
  `queryParameters["x"]` from the handler's block. `Requests.routeUrl`, `routeHeaders` and
  `routeOptional` turn a route into what "Open in Stream Inspector" writes, a `{{name}}` for each
  parameter.
- `streamlord-analysis`: `.streamlord/env.json` takes a fourth key, `params`, an object of texts.
  Each name in it is a variable for the URL, where its value is encoded, and the headers.
  `resolveRequest` reports a param that is missing or empty, and `withEnvVariables` adds it to
  `params` without touching the rest of the file. `Env` has a `params` field.
- `streamlord-analysis`: an env file that is not valid JSON is reported with where, such as
  `at line 3, column 1` for a comma after the last value.

### Removed

- `streamlord-analysis`: `Requests.pathParams` and `fillPath`, with the dialog they served; the
  inspector writes a `{{name}}` for each path parameter instead, through `Requests.routeUrl`.
- Spring Boot 3 support. The Spring adapter is compiled and tested against Spring Framework 7.0
  and Servlet 6.1 (Boot 4) only, and Streamlord requires kotlinx-coroutines 1.10.2, the version
  Boot 4 pins, instead of 1.8.1. `streamlord-json-jackson2` stays, for applications on Jackson 2.

### Fixed

- `streamlord-analysis`, for both editors: a route whose mapping had no leading slash, such as
  `@RequestMapping("api/orders")` or Ktor's `route("api")`, was found as `api/orders/view`, and
  the Stream Inspector opened it as `http://localhost:8080api/orders/view`. Every route path now
  starts with `/`.
- `streamlord-analysis`: `Requests.sendableUrl` refuses a host or a port that `java.net.URI`
  cannot read, such as `localhost:8080api`, `localhost:8080:` or `my_service`, and says which.
  The HTTP client used to refuse them later as "unsupported URI", naming only the URL. A test
  holds the rule to the JDK's own verdict.
- `streamlord-analysis`: `Requests.emptyBodyHint`, the note the Stream Inspector shows under an
  error response that came without a body: the reason is then in the server's log.
- Documentation: Operations said the client drops `last-event-id` when an event carries no id.
  It keeps it, and drops it only on an empty `id:`; the page now says so, with the source line.

## [0.11.1] - 2026-10-05

### Added

- Documentation: the site serves `/llms.txt`, an index of every page with what it is about, and
  `/llms-full.txt`, every page in one file, after llmstxt.org. Both are built from the pages and
  the navigation, and the link check covers their addresses.
- Documentation: the introduction and the README open with the same endpoint written by hand
  against the protocol and with Streamlord, and a screenshot of the editor refusing
  `dataText("$count")`. The introduction shows the three ways to write markup as tabs, and the
  README names them. The testing page says that Ktor 3.6.0's test host hangs on a streamed
  response of 1 MiB or more (KTOR-9968).
- Documentation: the testing page says that on Ktor 3.6.0 an exception thrown inside
  `respondDatastar` does not fail a test, and which assertions still catch it.

## [0.11.0] - 2026-10-04

### Added

- `streamlord-analysis`: `Requests.parseEnv`, which reads `.streamlord/env.json` with its three
  keys and reports anything else, and `mergeVariables`, `describeVariables`, `withEnvVariables`,
  `unreachableHint`, `variableCompletions` and `newRequestLabel`, which the Stream Inspector in
  both editors uses. `resolveRequest` now takes those variables and returns errors and the keys
  that are not set; `parseEnvFile` is gone.

### Fixed

- `streamlord-analysis`, for both editors: a Spring method took the `@RequestMapping` prefix of
  the class before it in the file. A class without `@RequestMapping` now has no prefix, a nested
  class keeps its mapping to itself, and a class mapping is no longer reported as a route when
  another annotation with parentheses in a string, such as `@PreAuthorize("hasRole('ADMIN')")`,
  stands before the class.
- `streamlord-analysis`, for both editors: `patchElements("#rows") { li { } }`, the kotlinx.html
  form, had its selector read as markup and flagged as text outside an element, and a mode
  without a selector went unreported. With a trailing lambda the first string is the selector
  and the second argument the mode.
- `streamlord-analysis`: `Requests.sendableUrl` gives the URL the Stream Inspector sends for the
  URL of a curl line, or the reason it cannot be sent as written.

## [0.10.1] - 2026-10-04

### Fixed

- `streamlord-spring` on Spring Boot: every `readSignals` and `datastarStream` threw
  `NoSuchMethodError: BuildersKt.runBlockingK$default`. Boot pins kotlinx-coroutines in its BOM
  (1.8.1 under Boot 3.5, 1.10.2 under Boot 4.0 and 4.1) and downgrades anything newer, and 0.10.0
  was compiled against 1.11.0. Every module is now compiled against 1.8.1, the oldest pin, and the
  Spring adapter's tests run on 1.8.1, on 1.10.2 with Framework 7, and on the newest release. On
  0.10.0 the way out is `ext["kotlin-coroutines.version"] = "1.11.0"` in your build.
- `streamlord-core`: a `StreamAuthorisation` refusal now holds when the handler does not stop.
  A handler that caught the exception inside a loop went on writing to a reader who had just
  been refused, with no further check. Every write after a refusal is now refused again, only
  `onRefused` may still write, and `Streamlord.stream` reports such a stream as refused.
- Documentation: the Spring WebMVC and WebFlux samples took `Streamlord` as a parameter of the
  handler method. Spring constructs a new default instance for such a parameter instead of
  injecting the bean, so the configured codec and `guardElements` were silently ignored. The
  samples now take the bean through the controller's constructor, and the build refuses a
  sample that does otherwise.
- `streamlord-core`: `interpolate` let a value into attributes the browser acts on. It now
  refuses a value in an event handler (`on*`) or `srcdoc`, takes only a number in `style`, and at
  the start of a URL attribute (`href`, `src`, `action` and the like) takes a relative URL or an
  `http`, `https`, `mailto` or `tel` one. `Position` gains `URL` and `STYLE`.
- `streamlord-html`: `dataNonce` refuses an empty or malformed nonce, as the plugin and the
  filter already did.
- `streamlord-core`: `ElementsGuard` threw `IllegalArgumentException` on a numeric character
  reference that names no character, such as `&#99999999;`. It is left as written.
- `streamlord-json-jackson2`: `Jackson2SignalsCodec.default()` refused a body carrying a signal
  the type does not name, which is every body from a page with more signals than one handler
  reads. It now ignores unknown properties, as Spring's mapper and the other codecs do.
- `streamlord-json-kotlinx`: a body of the wrong shape was reported with a hint to name a
  serializer for a native image. The hint now comes only with a failed serializer lookup.
- `streamlord-spring`: a WebFlux event wrote `retry: 1000`, the protocol default the encoder
  leaves off.
- Documentation, each checked by running it: the `$` trap ships the variable's value, not an
  empty string, and the runtime guard catches only the empty case; `signal()` and the other
  expression helpers refuse blank names and whitespace, not quotes; under Spring Boot's
  dependency-management plugin Boot's Jackson version wins in Gradle too; a body that will not
  parse is a `500` unless you map it; ten modules are published, not nine; and the bytes on the
  wire are identical on Ktor and WebMVC, while WebFlux writes the same frames in Spring's
  spelling.

- Every published jar now carries `META-INF/LICENSE`. The licence page said the licence text
  travels in the jar, and it did not.
- Documentation, from a register of 414 claims checked one by one: Spring Boot's default MVC
  executor runs eight streams at once and queues the rest, where the page said it created a
  thread per stream without limit; the golden files are compared as an equivalence, not byte for
  byte; `sharedLibrary.set(false)` is the default without `java-library`; stale coordinates and
  transcripts are rewritten rather than failing the build; completion collects signals from the
  whole project; and four smaller corrections on the roadmap and changelog pages.

- `streamlord-core`: a number of a million digits in a signal fits inside the default size limit
  and cost over twenty seconds of CPU the first time `int`, `long` or `decimal` read it. The
  parser now refuses a number longer than `JsonParser.MAX_NUMBER_LENGTH`, 1000 characters.
- `streamlord-core`: `JsonWriter` wrote a `BigDecimal` with an enormous exponent in plain
  notation, two billion zeros for `1E+2000000000`. It now keeps scientific notation past a scale
  of 100. A `Float` is written in its own shortest form, a map whose keys are written alike is
  refused, and the writer stops at the depth the parser stops at. The parser no longer takes the
  digits of other scripts in a `\u` escape. `JsonObject` and `JsonArray` equality against a plain
  `Map` or `List` now holds in both directions.
- `streamlord-core`: `SseDecoder` read streams the browser would not act on. A last message with
  no blank line after it is no longer an event, a line may end in a bare CR, a leading byte order
  mark is dropped, mode and namespace are matched as written, a signal patch with no `signals` is
  malformed, an event that is not Datastar's is skipped whatever its fields hold, and an empty
  `id` is no id.
- `streamlord-test`: `assertSignal` honours `onlyIfMissing` leaf by leaf, as the client's merge
  does; compares numbers by value, so `13` is `13.0`; takes `null` to mean the signal is not
  there; finds a key that holds a dot; and fails with an `AssertionError` on signals that are not
  JSON. `assertExactly` compares the frames two events put on the wire, so it takes the
  `ExecuteScript` that was sent and ignores a `retry` of the protocol default.
- `streamlord-html`: `dataBind { prop = "valueAsNumber" }` wrote the property in camelCase into
  the attribute name, which the browser lowercases; it is written `value-as-number`, which the
  client turns back. A name holding `__` is refused, because Datastar reads modifiers from there.
  A positive duration shorter than a millisecond is refused instead of written as zero.
  `SignalFilter.regexLiteral` writes an empty pattern as `/(?:)/`, escapes a line break, and
  refuses inline flags. `JsLiteral` quotes `__proto__`.
- `streamlord-core`: `ExpressionGuard` no longer refuses an expression holding a comment, a regex
  after `=>`, or a number ending in a dot.

- `streamlord-analysis`, and with it both editors, from a review that reproduced each of these:
  ordinary attributes such as `data-id`, `data-test` and `data-href` were flagged as misspelt
  Datastar attributes, with a fix that rewrote them; HTML with optional end tags (`<li>a<li>b`)
  was an error; `__debounce.500`, `$foo.0.name`, HTML entities in an attribute value and an `@`
  inside a string were all refused though Datastar accepts them; a string followed by
  `.trimIndent()` was not checked at a call site, so the CI check for the `$` trap missed it; a
  positional selector in `patchElements` was not seen; a quick fix on an escaped dollar wrote a
  live Kotlin template; U+0130 in the text shifted every later offset; a CRLF split across two
  chunks broke an SSE message in two; and `map.get("key")` was reported as a route. New errors
  say what the Datastar client refuses at run time: `action-space`, `missing-value`,
  `key-and-value` and `missing-key-or-value`.
- `streamlord-analysis`: the JavaScript parser is now a port of acorn, the parser the VS Code
  extension uses. Over a corpus of 682 expressions the two disagreed on the verdict for 67 and
  now disagree on none.
- VS Code extension: a quick fix for an expression inside an HTML attribute edited the start of
  the tag instead of the expression.
- `signalDrift` collects JSON-form declarations, honours `@SerialName`, and counts an accessor
  as a signal read only on a value that came from `readSignals`.

### Removed

- `streamlord-html`: the deprecated aliases `html.ExpressionGuard` and
  `html.InterpolatedExpressionException`, left from 0.1.1. With both `core.domain.*` and `html.*`
  imported they made either name ambiguous. Import them from `core.domain`.

### Added

- `streamlord-spring`: `Flow<DatastarEvent>.asDatastarResponse(streamlord)`, the WebFlux stream
  as a `ResponseEntity` carrying `Cache-Control: no-cache` and `X-Accel-Buffering: no`. A
  controller returning the bare flow from `asServerSentEvents` sent neither, so nginx held the
  events back until the stream ended.

## [0.10.0] - 2026-10-02

### Added

- `streamlord-test`, a new module: `datastarEvents(wire)` reads a Datastar response back into the
  events it carried, and asserts on what a handler meant rather than on the bytes it produced.
  `assertPatchElements`, `assertPatchSignals`, `assertSignal`, `assertNoSignal`, `assertNoEvents`
  and `assertExactly`. Every assertion but the last is satisfied by one matching event and ignores
  the rest, so a test about markup does not go red because a handler started patching a signal.
  `assertSignal` folds the whole stream the way the browser does, so it reports what a signal
  ended up as. It depends on `streamlord-core` and nothing else, and binds no test framework: a
  failure is an `AssertionError`, so kotlin.test, JUnit, Kotest and TestNG all work and none is
  imposed.
- `streamlord-core`: `SseDecoder`, the inverse of `SseEncoder`. `decode(text)` gives the events a
  stream carried and `messages(text)` the raw SSE messages, for a test about a heartbeat comment
  or a resume id. It is held to the same twenty official golden files as the encoder, in both
  directions.
- `streamlord-core`: `mergePatch` moves here from `streamlord-analysis`, into `core.json`. RFC
  7386 is how the protocol defines a signal patch, so anything asking what the browser ends up
  holding has to fold them the same way, and that is no longer the editor tooling's private
  business.

## [0.9.0] - 2026-10-02

### Removed

- `streamlord-core`: `Flow<DatastarEvent>.asSse()`. Of 244 public names across the published
  modules it was the only one with no caller anywhere: not in the tests, the demo, the editors,
  the documentation's examples or the core itself, and no page ever mentioned it. It was one line
  of sugar over `Streamlord.encode(flow)`, which is still there. `explicitApi()` makes you write
  the word `public`; it does not ask whether you meant it.

### Added

- `streamlord-core`: tests for `Streamlord.encode(flow)`, which `asSse` was the only caller of and
  which nothing had exercised: the frames it produces, an empty flow, and that `guardElements`
  stops an eaten expression before it becomes one.

## [0.8.0] - 2026-10-02

### Added

- `streamlord-core`: `StreamAuthorisation`, a check a stream carries for as long as it runs. A
  stream is authorised once and then lives for minutes while the reader logs out or an
  administrator revokes access, and nobody asks again. Pass one where you open the stream and
  Streamlord asks before writing, at the open and then no more often than `every` (five seconds by
  default), so a chatty stream does not cost a database round trip per patch. A refusal runs
  `onRefused` on the still-open stream, so the reader is told rather than dropped, and then ends
  the response rather than letting it fall silent.
- `streamlord-ktor`: `respondDatastar(authorisation = ...)` on both the block and the flow forms.
- `streamlord-spring`: `datastarStream(streamlord, request, authorisation) { }`, likewise.
- `streamlord-core`: `Streamlord.stream(sink, authorisation, block)` runs a stream to the end and
  reports whether it got there. `StreamRefusedException` is what ends a refused one; the adapters
  catch it where they opened the stream.

## [0.7.0] - 2026-10-01

### Changed

- `streamlord-analysis`: `signalDrift` returns a `SignalDriftReport` rather than the issue map
  alone. An empty result means nothing unless the check recognised reads at all, and the report's
  `read` and `declared` sets are how a caller tells a clean project from a check that matched
  nothing. That failure is why this exists: the first version called a 45-file corpus clean
  because its pattern missed every signals class.
- `streamlord-json-kotlinx`: `KotlinxSignalsCodec(strict = ...)` is now `requireNamedSerializers`.
  It never had anything to do with refusing unknown signals, which is the `Json` configuration's
  `ignoreUnknownKeys`, and a flag called `strict` on a signals codec reads as though it did.

### Fixed

- `streamlord-core`: `ExecuteScript`'s documentation offered its attribute map as a place for a
  CSP nonce. Under CSP mode Datastar re-creates every script it patches in and sets the nonce
  itself from the page's, so one passed there is overwritten, and with CSP mode off it does
  nothing. The nonce that matters is the one on the document's `<html>`.

## [0.6.0] - 2026-10-01

### Added

- `streamlord-analysis`: `signalDrift(facts)` reports every signal a handler reads that no markup
  in the project declares, which is the failure that compiles, deploys and then hands the handler
  a default on every request. `collectSignalFacts(src, language)` reads one file for it, and the
  check runs across the set, because the page that declares a signal is almost never the file
  that reads it. Only classes something reads signals into are examined; the drift in the other
  direction is not reported, since a signal nobody reads is harmless.
- `streamlord-analysis`: `collectDeclaredSignals(src, language)`, the declarations alone.
  `collectSignals` is unchanged and is still those plus the properties of `@Serializable` classes.

### Fixed

- `streamlord-analysis`: a `@Serializable` class carrying any modifier before `class` was not
  seen, so `public data class` signal classes, which is what an `explicitApi()` module writes,
  contributed no property names to completion.

## [0.5.0] - 2026-10-01

### Added

- `streamlord-core`: `interpolate(format, vararg values)` writes values into markup at its `%s`
  placeholders and decides what to do with each by where it landed, not by where it came from.
  Element text and ordinary quoted attributes are escaped. A placeholder in a `data-*` attribute
  Datastar reads, in a tag name, an attribute name, an unquoted value, or inside a `<script>` or
  `<style>` is refused with `UnsafeInterpolationException`, because escaping cannot make those safe.
  A number or a boolean is admitted into a Datastar attribute, so a server-rendered initial signal
  needs no waiver, and `Trusted(html)` waives both the escaping and the refusals for a fragment you
  rendered yourself.
- `streamlord-core`: `Position`, the five places a value can land, which
  `UnsafeInterpolationException` reports.

### Changed

- `streamlord-core`: `ElementsGuard` now walks markup through the same scanner `interpolate` uses,
  so there is one parser rather than two. Its behaviour is unchanged; it allocates one visitor per
  call where it previously allocated none.

## [0.4.0] - 2026-10-01

### Added

- `streamlord-core`: `CspNonce` in `core.domain` holds the half of Datastar's CSP mode that is the
  same in every framework: `generate()` for a per-response nonce of 16 bytes from `SecureRandom`,
  `starterPolicy(nonce)` for somewhere to start, `checked(nonce)` for a nonce from a generator of
  your own, and the two header names.
- `streamlord-ktor`: `CspNoncePlugin` generates one nonce per call, writes the
  `Content-Security-Policy` header from it, and hands the same value back through
  `call.cspNonce` for `dataNonce` on the `<html>` element. Both ends come from one variable, so
  they cannot drift apart, which until now nothing enforced. It is route-scoped, so an
  application that serves documents from some routes and streams from others installs it where
  the documents are. The policy is configurable, `null` writes no header, and `reportOnly = true`
  writes the report-only header instead.
- `streamlord-spring`: `CspNonceFilter` does the same for WebMVC, with `request.cspNonce` to read
  it back. For WebFlux, `exchange.installCspNonce()` writes both ends from inside a `WebFilter`
  you register yourself; the adapter ships no `WebFilter` of its own, because one would put
  Reactor on its compile classpath.

## [0.3.1] - 2026-09-29

### Added

- `streamlord-json-kotlinx`: `KotlinxSignalsCodec` takes the serializers it should use, by the
  type they handle, and consults them before the reflective lookup. A named serializer is the one
  the compiler plugin generated, resolved at the call site, so it is visible to a GraalVM native
  image, where reading a class to find its serializer cannot work. Passing none keeps the old
  behaviour exactly.
- `streamlord-json-kotlinx`: `strict = true` refuses the reflective lookup for a type that was not
  named, so the omission fails on the JVM with the name of the class instead of inside an image on
  the first request that carries it. It asks only for what an image cannot resolve by itself: your
  own classes, and containers holding them.

### Fixed

- Every module published an empty javadoc jar. The jar now carries the API reference generated
  from the KDoc in the source, so javadoc.io has something to show. Releases up to and including
  0.3.0 are on Maven Central with the empty one and cannot be changed.
- `streamlord-json-kotlinx`: a failure to encode or decode now says when no serializer was named
  for the type, and what to add. It used to say only that the value could not be decoded, which
  sends a reader to look at their JSON rather than at their codec.
- `streamlord-analysis`: a hyphen after a signal is read by Datastar 1.0.4 as part of the name
  (`$foo-bar` is `$['foo-bar']`, `$count-1` is `$['count-1']`; only `$a-$b` is a subtraction).
  The `signal-kebab` issue now says so, covers `$count-1` and the scope-variable cases it
  excused before, and offers the spaced subtraction beside the camelCase signal.

## [0.3.0] - 2026-09-27

### Added

- `streamlord-analysis`: the editor-independent analysis behind the editor tooling, as a Kotlin
  module with no dependency beyond `streamlord-core`. `Analyzer().analyzeKotlin(source)` and
  `analyzeHtml(source)` return every issue with its offset, severity, message, reference link and
  quick fixes: the Kotlin `$` interpolation trap, expression syntax (an in-house JavaScript
  parser, positioned like acorn), unknown and Pro actions, the markup rules of a patch, the
  `data-*` attribute rules, the casing rules and the wire hint. Also there: the Kotlin string
  reader (escapes, templates, multi-dollar literals, an offset map), the call-site scanner, the
  signal and selector collectors, the Ktor and Spring route finder, the Stream Inspector's file
  format and curl export, the SSE parser and the JSON merge patch, the hover documentation and the
  highlighting tokenizer. Tested against the cases of the VS Code extension, so the two editors
  agree.
- `editors/intellij`: the Streamlord plugin for IntelliJ IDEA 2026.1 and newer, Community and
  Ultimate. Inspections with quick fixes, completion, hover documentation, highlighting of the
  Datastar tokens, HTML injection into marker-less HTML strings, live templates, and the Stream
  Inspector as a tool window with saved requests shared with VS Code, variables, gutter icons on
  routes and curl export. Published to the JetBrains Marketplace from CI on an `intellij-v*` tag.

### Fixed

- An expression that opens with `{` is read as the object literal Datastar makes of it
  (`return ({count: 0, name: ''})`), in the VS Code extension and the analysis alike; a script
  parser saw a block and flagged `data-signals="{count: 0, name: ''}"` as a syntax error.

## [0.2.0] - 2026-09-27

### Added

- `streamlord-core`: `ElementsGuard`, the `$` trap hunted in HTML written as a string. It walks
  the tags, finds every `data-*` attribute whose value is an expression and runs it through
  `ExpressionGuard`; the exception names the attribute. `Streamlord(guardElements = true)`
  applies it to every element patch and elements response that leaves through that instance:
  streams, `encode(flow)`, Ktor's `respondDatastar(response)`, Spring's `datastarElements` and
  `asServerSentEvents`. Off by default. The Ktor plugin takes it as
  `install(StreamlordPlugin) { guardElements = true }`. The same guard catches a mistyped
  attribute: a `data-*` name one letter away from a Datastar attribute (`data-onn:click`,
  `data-signal:x`) throws `MistypedAttributeException` naming the attribute it resembles,
  while names further away are taken to be your own and left alone. A bare name without key
  or modifier is only judged when a letter is swapped in a long name (`data-indicater`,
  `data-computer`), so `data-test`, `data-kind`, `data-animated` and `data-effects` pass.
  `<script>` and `<style>` bodies are skipped.
  `ElementsGuard.attributes` lists every Datastar attribute name, bound to the catalog by test.
- `ExpressionGuard` accepts `[]`, `{}`, spread (`...$items`) and leading-dot decimals (`.5`),
  which it refused before as operator-only or dangling, and now catches what an eaten signal
  leaves inside a literal or argument list (`{count: }`, `{n: , m: 1}`, `[, 1]`), a missing
  left operand of `<` and a missing right operand of `+` or `-` (`'Hello, ' + `), which it let
  through before, while `$count ++` with a space, valid JavaScript, is no longer refused.
  `ElementsGuard` decodes the HTML entities kotlinx.html and template engines write
  (`&amp;&amp;`, `&lt;`, `&#x27;`) once, as the browser does, before judging a value; skips
  `<script>`, `<style>`, `<textarea>` and `<title>` bodies; and string and regex literals inside
  an expression (`':)'`, `split(/[,;]/)`, `/(?:a|b)/`) are never read as operators, which
  0.1.1's guard got wrong for `(?` and `(=`. `Streamlord(attributePrefixes = ...)` and
  `ElementsGuard.check(html, prefixes)` take a custom bundle alias, longest matched first.
  `Flow<DatastarEvent>.asSse()` takes the instance too.
- `catalog/datastar-1.0.4.json`: `keyCase` on every keyed attribute (`camel`, `kebab` or
  `raw`), the one place the casing rule is written; the catalog test binds `Casing` to it and
  the extension reads it.
- `@Language("HTML")`, `@Language("JSON")` and `@Language("JavaScript")` on every `elements`,
  `signals` and `script` parameter in core, Ktor and Spring, so IntelliJ injects the right
  language into a literal passed straight in. The annotation's artifact,
  `org.jetbrains:annotations`, is declared `compileOnly`, so nothing new reaches a consumer;
  kotlin-stdlib ships the same artifact anyway.
- README: "Three ways to write markup", covering the DSL, strings (`$$"""..."""`,
  `@Language("HTML")`) and templates as equals, with what each gets from the SDK and the editors.
- `streamlord-html`: `Casing`, the casing rules of Datastar applied so that the name you write
  is the name you get. The browser lowercases attribute names, so the keyed helpers write a
  camelCase name as the kebab-case key Datastar reads back as that name (`dataSignals("fooBar")`
  is `data-signals:foo-bar`, the signal `$fooBar`), adding `__case.camel` for `dataOn` and
  `dataClass` where Datastar's default is kebab, and `__case.pascal` for a leading capital.
  `dataAttr`, `dataStyle`, `dataAnimate` and `dataPersist` write the key in kebab-case. `signal()` and the
  other expression helpers read a kebab-case key the way Datastar names it (`signal("foo-bar")`
  is `$fooBar`). `InvalidSignalNameException` for a blank name or one with whitespace, quotes
  or a character that ends an attribute; `hover:bg-red-500`, `xlink:href` and `--brand` are
  fine as keys, and a reference is JavaScript, so `items[0].name` and `items['sub-total']`
  are fine there. An explicit `case =` is kept, with the key still in kebab-case.

### Changed

- `ExpressionGuard` and `InterpolatedExpressionException` moved from `streamlord-html` to
  `streamlord-core` (`io.github.markusaugust.streamlord.core.domain`), so the string and
  template paths can use them without the DSL. `InterpolatedExpressionException` gained an
  `attribute` property.
- `datastarElements`, `DatastarResponse.toResponseEntity` (Spring), `asServerSentEvents` and
  `DatastarEvent.toServerSentEvent` (Spring WebFlux) take an optional `streamlord` parameter,
  last, for the guard; Spring has no auto-configuration, so pass your bean. `Streamlord` gained the `guardElements`
  constructor parameter, also last. Kotlin callers recompile unchanged; Java callers of the
  full constructor pass one more argument.

### Deprecated

- The `streamlord-html` names `ExpressionGuard` and `InterpolatedExpressionException` remain as
  type aliases of the core ones.

### Fixed

An audit of the DSL against the Datastar 1.0.4 client source and bundles found these; the
protocol layer came through it unchanged.

- `streamlord-html`: `js()`, `set()`, `setAll()`, the fetch options (`headers`, `payload`) and
  the Pro actions (`clipboard()`, `intl()`) write JavaScript literals in single quotes through
  the new `JsLiteral`, as the Datastar documentation does: `@get('/x')`, `$name = 'Gorvek'`,
  `{headers: {'X-Csrf-Token': 't'}}`. The JSON they wrote before survived only the kotlinx.html
  DSL, which escapes double quotes; pasted into a double-quoted attribute in a string or a
  template, it ended the attribute. `dataSignals(vararg)` still writes JSON, which is an
  attribute value only.
- `streamlord-html`: `dataBind`, `dataRef` and `dataIndicator` wrote `__case` on the value form
  (`data-bind__case.kebab="foo"`), where Datastar ignores it, since casing applies to a key
  only. With a `case` they now write the key form (`data-bind:foo__case.kebab`), through
  `Casing.key`; without one they write the value, reading a kebab-case name as Datastar names
  the signal (`dataBind("foo-bar")` is `data-bind="fooBar"`, matching `signal("foo-bar")`).
  The `case` parameter of the object forms `dataSignals("{...}")`, `dataComputed("{...}")`
  and `dataClass("{...}")` was equally inert and is removed; the keys of the object are used
  as written.
- `streamlord-html`: `SignalFilter.regexLiteral` dropped the `RegexOption`s, so
  `Regex("user", IGNORE_CASE)` reached the browser as `/user/`. `IGNORE_CASE`, `MULTILINE`
  and `DOT_MATCHES_ALL` become the `i`, `m` and `s` flags; the options with no JavaScript
  equivalent throw `IllegalArgumentException`. A `/` that the pattern had already escaped is
  no longer escaped twice.
- `streamlord-html`: `setAll()` and `toggleAll()` write `include: /.*/` in front of a filter
  that only excludes, matching the documented signature of the two actions.
- `streamlord-html-pro`: `dataMatchMedia` wrapped the query in bare quotes; a `'` in the query
  broke the expression. It is quoted with `js()`.
- `streamlord-core`: `ExecuteScript`'s `data-effect="el.remove()"` follows the attribute
  prefix, so the aliased bundle gets `data-star-effect` and the script tags are removed as
  intended. The switch is `DatastarAttributes.prefix` in `core.protocol`, and the
  `streamlord-html` object of the same name now sets it.
- `streamlord-core`: `redirect()` navigates from a `setTimeout`, as the official SDKs do, so the
  script element is applied and removed before the page unloads.
- `streamlord-spring`: `datastarStream` and `prepareForSse` take an optional
  `HttpServletRequest`; with it, `Connection: keep-alive` is only set for HTTP/1.1, as the SDK
  specification says and HTTP/2 requires.
- Documentation: the default `filterSignals` exclusion is `/(^|\.)_/`, which also drops nested
  `_` keys such as `user._token`; and Datastar Rocket, the separate beta bundle with a
  web-component API, is stated to be outside what the DSL covers.

## [0.1.1] - 2026-09-26

### Added

- `streamlord-html`: `ExpressionGuard` and `InterpolatedExpressionException`. Every expression
  helper in `streamlord-html` and `streamlord-html-pro` now refuses, at render time, text that
  has the shape a Kotlin string template leaves behind when it interpolates a signal
  (`"$count++"` arriving as `"++"`). The message names the helpers and the `$$"..."` literal.
- README: a section on the `$` trap and the three ways to avoid it.

### Fixed

- CI: the Maven Central publish job no longer fires on `vscode-v*` tags.

## [0.1.0] - 2026-09-26

The first forging.

### Added

- `editors/vscode`: the Streamlord VS Code extension (diagnostics, completions, hover,
  highlighting, Stream Inspector) and `catalog/datastar-1.0.4.json`, the shared source of
  truth verified by `CatalogTest`.

- `streamlord-core`: Datastar 1.0.4 protocol model (`PatchElements`, `PatchSignals`,
  `ExecuteScript`), `SseEncoder` verified against the official SDK golden files, non-SSE
  `DatastarResponse` types, a dependency-free strict JSON parser and writer, the
  `DatastarStream` driving port and the `SseSink`, `SignalsCodec` and `IncomingRequest`
  driven ports, and the `Streamlord` facade with bounded signal reading.
- `streamlord-html`: kotlinx.html DSL for every Datastar attribute, modifier, action and
  expression helper; `patchElements { }` for streams, events and responses.
- `streamlord-ktor`: `respondDatastar`, `respondElements`/`respondSignals`/`respondScript`,
  `readSignals`, the `StreamlordPlugin`, and an in-process run of the official SDK
  conformance server.
- `streamlord-spring`: `datastarStream` over `StreamingResponseBody`, `readSignals` over
  `HttpServletRequest`, `ResponseEntity` helpers, `Flow<DatastarEvent>.asServerSentEvents()`
  for WebFlux, and `ElementPatchModeConverter`.
- `streamlord-json-kotlinx`, `streamlord-json-jackson` (Jackson 3, Spring Boot 4) and
  `streamlord-json-jackson2` (Jackson 2, Spring Boot 3): codec adapters for typed signals.
- `streamlord-html`: `payload` and `responseOverrides` fetch options, `dataOnFetch` with
  `FetchEventType` constants, and `DatastarAttributes.prefix` for the aliased `data-star-*` bundle.
- `streamlord-html-pro`: opt-in helpers for the attribute names and actions of Datastar Pro,
  containing no Pro code. The Spring adapter is compiled against Spring Framework 6.2 and
  tested against 6.2 and 7.0.
