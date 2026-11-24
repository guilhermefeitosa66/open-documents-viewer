package io.github.guilhermefeitosa66.opendocumentsviewer.syntax

class Language(
    val id: String,
    val name: String,
    val badge: String,
    val extensions: Set<String> = emptySet(),
    val fileNames: Set<String> = emptySet(),
    lexerFactory: () -> Lexer,
) {
    /** Built on first use: opening a Kotlin file never builds the SQL keyword tables. */
    val lexer: Lexer by lazy(lexerFactory)
    override fun toString() = name
}

private fun words(s: String): Set<String> = s.trim().split(Regex("\\s+")).toSet()

private val cStrings = listOf(StringRule("\""), StringRule("'", maxLength = 12))
private val cComments = listOf("//")
private val cBlock = listOf("/*" to "*/")

object Languages {
    val KOTLIN = Language("kotlin", "Kotlin", "KT", setOf("kt", "kts"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""package import class interface object fun val var if else when for while do return
                    break continue throw try catch finally in is as typealias this super where by get set init constructor
                    companion data sealed enum annotation open abstract override private protected public internal
                    lateinit inline noinline crossinline reified suspend operator infix tailrec external const vararg out
                    actual expect value"""),
                literals = words("true false null"),
                types = words("Int Long Short Byte Double Float Boolean Char String Unit Any Nothing Array List Map Set"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(
                    StringRule("\"\"\"", multiline = true, escape = null, interpolation = true),
                    StringRule("\"", interpolation = true),
                    StringRule("'", maxLength = 10),
                ),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    val JAVA = Language("java", "Java", "JAVA", setOf("java"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""abstract assert break case catch class const continue default do else enum extends final
                    finally for goto if implements import instanceof interface native new package private protected public
                    return static strictfp super switch synchronized this throw throws transient try volatile while var
                    record sealed permits yield non-sealed"""),
                literals = words("true false null"),
                types = words("boolean byte char double float int long short void"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\""), StringRule("'", maxLength = 10)),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    val GROOVY = Language("groovy", "Groovy / Gradle", "GRDL", setOf("groovy", "gradle", "gvy", "jenkinsfile"), setOf("jenkinsfile"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""def class interface enum trait extends implements import package return if else for while
                    switch case default break continue try catch finally throw new in as instanceof static final private
                    public protected abstract void"""),
                literals = words("true false null"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(
                    StringRule("\"\"\"", multiline = true, interpolation = true), StringRule("'''", multiline = true),
                    StringRule("\"", interpolation = true), StringRule("'"),
                ),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    val SCALA = Language("scala", "Scala", "SCALA", setOf("scala", "sc", "sbt"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""abstract case catch class def do else extends final finally for forSome if implicit import
                    lazy match new object override package private protected return sealed super this throw trait try type
                    val var while with yield given using enum then export"""),
                literals = words("true false null"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("\"\"\"", multiline = true, escape = null), StringRule("\""), StringRule("'", maxLength = 10)),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    private val cKeywords = """auto break case const continue default do else enum extern for goto if inline register
        restrict return sizeof static struct switch typedef union volatile while _Bool _Complex"""
    private val cTypes = "char double float int long short signed unsigned void bool size_t int8_t int16_t int32_t int64_t uint8_t uint16_t uint32_t uint64_t FILE"

    val C = Language("c", "C", "C", setOf("c", "h"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("$cKeywords #include #define #ifdef #ifndef #endif #if #else #elif #pragma #undef"),
                literals = words("NULL true false"),
                types = words(cTypes),
                lineComments = cComments, blockComments = cBlock, strings = cStrings,
                identExtra = "#",
            )
        )
    })

    val CPP = Language("cpp", "C++", "CPP", setOf("cpp", "cc", "cxx", "hpp", "hh", "hxx", "ino", "ipp"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words(
                    "$cKeywords #include #define #ifdef #ifndef #endif #if #else #elif #pragma #undef " +
                        """class namespace template typename public private protected virtual override final new delete this
                        using try catch throw operator friend explicit constexpr consteval constinit noexcept static_cast
                        dynamic_cast reinterpret_cast const_cast decltype mutable concept requires co_await co_return co_yield"""
                ),
                literals = words("NULL nullptr true false"),
                types = words("$cTypes auto std string vector map wchar_t char8_t char16_t char32_t"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("R\"(", ")\"", multiline = true, escape = null)) + cStrings,
                identExtra = "#",
            )
        )
    })

    val CSHARP = Language("csharp", "C#", "CS", setOf("cs", "csx"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""abstract as base break case catch checked class const continue default delegate do else
                    enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock
                    namespace new operator out override params private protected public readonly ref return sealed sizeof
                    stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while async
                    await var record init get set when where yield"""),
                literals = words("true false null"),
                types = words("bool byte char decimal double float int long object sbyte short string uint ulong ushort void dynamic"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("\"\"\"", multiline = true, escape = null), StringRule("@\"", "\"", multiline = true, escape = null), StringRule("\""), StringRule("'", maxLength = 10)),
                capitalizedTypes = true,
            )
        )
    })

    val GO = Language("go", "Go", "GO", setOf("go"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""break case chan const continue default defer else fallthrough for func go goto if import
                    interface map package range return select struct switch type var"""),
                literals = words("true false nil iota"),
                types = words("bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("`", multiline = true, escape = null), StringRule("\""), StringRule("'", maxLength = 10)),
            )
        )
    })

    val RUST = Language("rust", "Rust", "RS", setOf("rs"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""as async await break const continue crate dyn else enum extern fn for if impl in let loop
                    match mod move mut pub ref return self Self static struct super trait type unsafe use where while
                    macro_rules"""),
                literals = words("true false None Some Ok Err"),
                types = words("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("r#\"", "\"#", multiline = true, escape = null), StringRule("\"", multiline = true), StringRule("'", maxLength = 12)),
                annotationPrefix = '#', capitalizedTypes = true,
            )
        )
    })

    val SWIFT = Language("swift", "Swift", "SWFT", setOf("swift"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""associatedtype class deinit enum extension fileprivate func import init inout internal let
                    open operator private protocol public rethrows static struct subscript typealias var break case continue
                    default defer do else fallthrough for guard if in repeat return switch where while as catch is super
                    self Self throw throws try async await actor some any mutating override final lazy weak unowned"""),
                literals = words("true false nil"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\"")),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    val DART = Language("dart", "Dart", "DART", setOf("dart"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""abstract as assert async await base break case catch class const continue covariant default
                    deferred do dynamic else enum export extends extension external factory final finally for get hide if
                    implements import in interface is late library mixin new on operator part required rethrow return sealed
                    set show static super switch sync this throw try typedef var void when while with yield"""),
                literals = words("true false null"),
                types = words("int double num bool String List Map Set Future Stream Object"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(
                    StringRule("\"\"\"", multiline = true, interpolation = true), StringRule("'''", multiline = true, interpolation = true),
                    StringRule("\"", interpolation = true), StringRule("'", interpolation = true),
                ),
                annotationPrefix = '@', capitalizedTypes = true,
            )
        )
    })

    private val jsKeywords = """break case catch class const continue debugger default delete do else export extends finally
        for function if import in instanceof let new return super switch this throw try typeof var void while with yield
        async await of static get set from as"""

    val JAVASCRIPT = Language("javascript", "JavaScript", "JS", setOf("js", "mjs", "cjs", "jsx"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words(jsKeywords),
                literals = words("true false null undefined NaN Infinity"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("`", multiline = true, interpolation = true), StringRule("\""), StringRule("'")),
                annotationPrefix = '@', identExtra = "$", capitalizedTypes = true,
            )
        )
    })

    val TYPESCRIPT = Language("typescript", "TypeScript", "TS", setOf("ts", "tsx", "mts", "cts"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("$jsKeywords interface type enum implements namespace declare abstract private protected public readonly keyof infer is satisfies"),
                literals = words("true false null undefined NaN Infinity"),
                types = words("string number boolean any unknown never void object bigint symbol"),
                lineComments = cComments, blockComments = cBlock,
                strings = listOf(StringRule("`", multiline = true, interpolation = true), StringRule("\""), StringRule("'")),
                annotationPrefix = '@', identExtra = "$", capitalizedTypes = true,
            )
        )
    })

    val PYTHON = Language("python", "Python", "PY", setOf("py", "pyw", "pyi"), setOf("sconstruct", "sconscript"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""and as assert async await break class continue def del elif else except finally for from
                    global if import in is lambda nonlocal not or pass raise return try while with yield match case self"""),
                literals = words("True False None"),
                types = words("int float str bool list dict set tuple bytes object type"),
                lineComments = listOf("#"),
                strings = listOf(
                    StringRule("\"\"\"", multiline = true), StringRule("'''", multiline = true),
                    StringRule("\""), StringRule("'"),
                ),
                annotationPrefix = '@',
            )
        )
    })

    val RUBY = Language("ruby", "Ruby", "RB", setOf("rb", "rake", "gemspec", "ru"), setOf("gemfile", "rakefile", "podfile", "fastfile", "vagrantfile", "brewfile", "guardfile", "appfile"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""alias and begin break case class def defined? do else elsif end ensure for if in module next
                    not or redo rescue retry return self super then undef unless until when while yield require attr_reader
                    attr_accessor attr_writer private protected public"""),
                literals = words("true false nil"),
                lineComments = listOf("#"),
                blockComments = listOf("=begin" to "=end"),
                strings = listOf(StringRule("\"", interpolation = false), StringRule("'")),
                variablePrefix = '@', identExtra = "?!", capitalizedTypes = true,
            )
        )
    })

    val PHP = Language("php", "PHP", "PHP", setOf("php", "phtml"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""abstract and array as break callable case catch class clone const continue declare default
                    do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn
                    for foreach function global goto if implements include include_once instanceof insteadof interface isset
                    list match namespace new or print private protected public readonly require require_once return static
                    switch throw trait try unset use var while yield enum"""),
                literals = words("true false null TRUE FALSE NULL"),
                lineComments = listOf("//", "#"), blockComments = cBlock,
                strings = listOf(StringRule("\"", interpolation = true), StringRule("'")),
                variablePrefix = '$', capitalizedTypes = true,
            )
        )
    })

    val SHELL = Language(
        "shell", "Shell", "SH", setOf("sh", "bash", "zsh", "ksh", "fish", "command"),
        setOf(".bashrc", ".zshrc", ".profile", ".bash_profile", ".zprofile", ".bash_aliases", "pkgbuild", "apkbuild"),
        lexerFactory = {
            GenericLexer(
                LanguageSpec(
                    keywords = words("""if then else elif fi case esac for while until do done in function return break continue
                        local export readonly declare unset shift exit source alias set eval exec trap select time"""),
                    literals = words("true false"),
                    lineComments = listOf("#"), commentNeedsBoundary = true,
                    strings = listOf(StringRule("\"", multiline = true, interpolation = true), StringRule("'", multiline = true, escape = null)),
                    variablePrefix = '$', identExtra = "-",
                )
            )
        },
    )

    val POWERSHELL = Language("powershell", "PowerShell", "PS1", setOf("ps1", "psm1", "psd1"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""begin break catch class continue data do dynamicparam else elseif end exit filter finally for
                    foreach from function if in param process return switch throw trap try until using var while"""),
                literals = words("\$true \$false \$null"),
                caseInsensitive = true,
                lineComments = listOf("#"), blockComments = listOf("<#" to "#>"),
                strings = listOf(StringRule("\"", interpolation = true, escape = '`'), StringRule("'", escape = null)),
                variablePrefix = '$', identExtra = "-",
            )
        )
    })

    val SQL = Language("sql", "SQL", "SQL", setOf("sql", "ddl", "psql"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("""select from where and or not insert into values update set delete create table alter drop
                    index view join left right inner outer full cross on as group by order having limit offset union all
                    distinct case when then else end primary key foreign references unique default check constraint begin
                    commit rollback transaction if exists in is like between returning with recursive grant revoke function
                    procedure trigger declare cascade asc desc"""),
                literals = words("null true false"),
                types = words("int integer bigint smallint serial bigserial text varchar char boolean bool date time timestamp timestamptz numeric decimal real float double uuid json jsonb blob bytea"),
                caseInsensitive = true,
                lineComments = listOf("--"), blockComments = cBlock,
                strings = listOf(StringRule("'", escape = null), StringRule("\"", escape = null)),
            )
        )
    })

    val LUA = Language("lua", "Lua", "LUA", setOf("lua"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("and break do else elseif end for function goto if in local not or repeat return then until while"),
                literals = words("true false nil"),
                lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"),
                strings = listOf(StringRule("[[", "]]", multiline = true, escape = null), StringRule("\""), StringRule("'")),
            )
        )
    })

    val R = Language("r", "R", "R", setOf("r"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("if else repeat while function for in next break library require return"),
                literals = words("TRUE FALSE NULL NA Inf NaN T F"),
                lineComments = listOf("#"),
                strings = listOf(StringRule("\""), StringRule("'")),
            )
        )
    })

    val PERL = Language("perl", "Perl", "PL", setOf("pl", "pm"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("my our local sub if elsif else unless while until for foreach do last next redo return use package require no and or not eq ne lt gt le ge"),
                lineComments = listOf("#"), commentNeedsBoundary = true,
                strings = listOf(StringRule("\"", interpolation = true), StringRule("'")),
                variablePrefix = '$',
            )
        )
    })

    val CSS = Language("css", "CSS", "CSS", setOf("css", "scss", "sass", "less"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("@media @import @font-face @keyframes @supports @layer @container @use @mixin @include !important"),
                literals = words("inherit initial unset none auto"),
                lineComments = listOf("//"), blockComments = cBlock,
                strings = listOf(StringRule("\""), StringRule("'")),
                variablePrefix = '$', identExtra = "-@!",
            )
        )
    })

    val DOCKERFILE = Language("dockerfile", "Dockerfile", "DOCK", setOf("dockerfile", "containerfile"), setOf("dockerfile", "containerfile"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS MAINTAINER"),
                lineComments = listOf("#"), commentNeedsBoundary = true,
                strings = listOf(StringRule("\"", interpolation = true), StringRule("'")),
                variablePrefix = '$',
            )
        )
    })

    val MAKEFILE = Language("make", "Makefile", "MAKE", setOf("mk", "mak"), setOf("makefile", "gnumakefile"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("ifeq ifneq ifdef ifndef else endif include define endef export override .PHONY"),
                lineComments = listOf("#"),
                strings = listOf(StringRule("\""), StringRule("'")),
                variablePrefix = '$', identExtra = ".",
            )
        )
    })

    val CMAKE = Language("cmake", "CMake", "CMAKE", setOf("cmake"), setOf("cmakelists.txt"), lexerFactory = {
        GenericLexer(
            LanguageSpec(
                keywords = words("if elseif else endif foreach endforeach while endwhile function endfunction macro endmacro set project add_executable add_library target_link_libraries include find_package"),
                caseInsensitive = true,
                lineComments = listOf("#"),
                strings = listOf(StringRule("\"", interpolation = true)),
                variablePrefix = '$',
            )
        )
    })

    val CONF = Language(
        "conf", "Config", "CONF", setOf("gitignore", "dockerignore", "npmignore", "gitattributes", "htaccess"),
        setOf(".gitignore", ".dockerignore", ".npmignore", ".gitattributes", ".htaccess", "codeowners", "requirements.txt", ".nvmrc", ".tool-versions", "procfile", "brewfile.lock", ".mailmap", ".prettierignore", ".eslintignore", "justfile"),
        lexerFactory = {
            GenericLexer(LanguageSpec(lineComments = listOf("#"), strings = listOf(StringRule("\""))))
        },
    )


    val JULIA = Language("julia", "Julia", "JL", setOf("jl"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""function end if elseif else for while return module using import export struct mutable begin let do
                try catch finally macro quote abstract primitive type const global local break continue where in isa"""),
            literals = words("true false nothing missing NaN Inf"),
            lineComments = listOf("#"), blockComments = listOf("#=" to "=#"),
            strings = listOf(StringRule("\"\"\"", multiline = true, interpolation = true), StringRule("\"", interpolation = true), StringRule("'", maxLength = 10)),
            annotationPrefix = '@', capitalizedTypes = true,
        ))
    })

    val HASKELL = Language("haskell", "Haskell", "HS", setOf("hs", "lhs"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""module where import qualified as hiding data type newtype class instance deriving if then else case of
                let in do forall infix infixl infixr default family"""),
            literals = words("True False Nothing Just Left Right"),
            lineComments = listOf("--"), blockComments = listOf("{-" to "-}"),
            strings = listOf(StringRule("\""), StringRule("'", maxLength = 10)),
            capitalizedTypes = true, identExtra = "'",
        ))
    })

    val ELIXIR = Language("elixir", "Elixir", "EX", setOf("ex", "exs", "heex", "eex"), setOf("mix.lock"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""def defp defmodule defmacro defstruct defprotocol defimpl defdelegate defguard do end fn case cond if else
                unless when with for receive after try catch rescue raise import alias use require quote unquote in and or not"""),
            literals = words("true false nil"),
            lineComments = listOf("#"),
            strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\""), StringRule("'")),
            annotationPrefix = '@', variablePrefix = ':', capitalizedTypes = true, identExtra = "?!",
        ))
    })

    val ERLANG = Language("erlang", "Erlang", "ERL", setOf("erl", "hrl"), setOf("rebar.config"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("after and andalso band begin bnot bor bsl bsr bxor case catch cond div end fun if let not of or orelse receive rem try when xor"),
            literals = words("true false undefined ok error"),
            lineComments = listOf("%"),
            strings = listOf(StringRule("\""), StringRule("'")),
            annotationPrefix = '-', variablePrefix = '?',
        ))
    })

    val LISP = Language("lisp", "Lisp / Clojure", "LISP", setOf("clj", "cljs", "cljc", "edn", "lisp", "lsp", "cl", "el", "scm", "ss", "rkt", "fnl"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""def defn defn- defmacro defmulti defmethod defrecord defprotocol deftype defun defvar defparameter defpackage
                let let* letfn fn lambda if when unless cond case do loop recur ns require import use quote progn setq setf define begin"""),
            literals = words("nil true false t"),
            lineComments = listOf(";"),
            strings = listOf(StringRule("\"", multiline = true)),
            variablePrefix = ':', identExtra = "-!?*<>=/+.&",
        ))
    })

    val OCAML = Language("ocaml", "OCaml", "ML", setOf("ml", "mli", "mll", "mly", "re", "rei"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""let in rec and match with function fun type module struct sig end open include if then else begin
                val mutable of when as try exception raise external functor object method inherit new private virtual for to downto do done while"""),
            literals = words("true false None Some"),
            blockComments = listOf("(*" to "*)"),
            strings = listOf(StringRule("\""), StringRule("'", maxLength = 6)),
            capitalizedTypes = true, identExtra = "'",
        ))
    })

    val FSHARP = Language("fsharp", "F#", "FS", setOf("fs", "fsi", "fsx"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""let in rec and match with function fun type module namespace open if then else elif begin end val mutable
                of when as try finally raise member static override abstract default interface inherit new do done for to downto while yield return async use"""),
            literals = words("true false null None Some"),
            lineComments = listOf("//"), blockComments = listOf("(*" to "*)"),
            strings = listOf(StringRule("\"\"\"", multiline = true, escape = null), StringRule("\""), StringRule("'", maxLength = 6)),
            annotationPrefix = '#', capitalizedTypes = true,
        ))
    })

    val ZIG = Language("zig", "Zig", "ZIG", setOf("zig", "zon"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""fn pub const var struct enum union opaque if else while for switch return try catch defer errdefer
                comptime inline export extern test break continue orelse and or error async await suspend resume threadlocal usingnamespace"""),
            literals = words("true false null undefined unreachable"),
            types = words("u8 u16 u32 u64 usize i8 i16 i32 i64 isize f32 f64 bool void anyerror anytype type noreturn"),
            lineComments = listOf("//"),
            strings = listOf(StringRule("\""), StringRule("'", maxLength = 10)),
            annotationPrefix = '@', capitalizedTypes = true,
        ))
    })

    val NIM = Language("nim", "Nim", "NIM", setOf("nim", "nims", "nimble"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""proc func method iterator template macro var let const type object enum tuple ref ptr if elif else when case of
                for while in notin is isnot return yield break continue import from include export try except finally raise discard defer block and or not"""),
            literals = words("true false nil"),
            lineComments = listOf("#"), blockComments = listOf("#[" to "]#"),
            strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\""), StringRule("'", maxLength = 6)),
            capitalizedTypes = true,
        ))
    })

    val BATCH = Language("batch", "Batch", "BAT", setOf("bat", "cmd"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("echo set if else for in do goto call exit setlocal endlocal shift pause start cd pushd popd not exist defined errorlevel"),
            caseInsensitive = true,
            lineComments = listOf("rem ", "REM ", "::", "@rem"),
            strings = listOf(StringRule("\"")),
            variablePrefix = '%',
        ))
    })

    val GRAPHQL = Language("graphql", "GraphQL", "GQL", setOf("graphql", "gql", "graphqls"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("query mutation subscription fragment on type input enum interface union scalar schema extend directive implements repeatable"),
            literals = words("true false null"),
            types = words("Int Float String Boolean ID"),
            lineComments = listOf("#"),
            strings = listOf(StringRule("\"\"\"", multiline = true), StringRule("\"")),
            variablePrefix = '$', annotationPrefix = '@', capitalizedTypes = true,
        ))
    })

    val PROTOBUF = Language("protobuf", "Protocol Buffers", "PROTO", setOf("proto"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("syntax edition package import option message enum service rpc returns repeated optional required oneof map reserved extend stream extensions to max public weak"),
            literals = words("true false"),
            types = words("double float int32 int64 uint32 uint64 sint32 sint64 fixed32 fixed64 sfixed32 sfixed64 bool string bytes"),
            lineComments = cComments, blockComments = cBlock,
            strings = listOf(StringRule("\""), StringRule("'")),
            capitalizedTypes = true,
        ))
    })

    val HCL = Language("hcl", "HCL / Terraform", "HCL", setOf("tf", "tfvars", "hcl", "nomad"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("resource data variable output module provider locals terraform for_each count dynamic content for in if depends_on lifecycle backend required_providers"),
            literals = words("true false null"),
            lineComments = listOf("#", "//"), blockComments = cBlock,
            strings = listOf(StringRule("\"", interpolation = true)),
            identExtra = "-",
        ))
    })

    val NIX = Language("nix", "Nix", "NIX", setOf("nix"), setOf("flake.lock"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("let in with rec inherit if then else assert import or"),
            literals = words("true false null"),
            lineComments = listOf("#"), blockComments = cBlock,
            strings = listOf(StringRule("''", multiline = true, escape = null, interpolation = true), StringRule("\"", interpolation = true)),
            identExtra = "-'",
        ))
    })

    val LATEX = Language("latex", "LaTeX", "TEX", setOf("tex", "sty", "cls", "ltx", "dtx", "ins"), lexerFactory = {
        GenericLexer(LanguageSpec(
            lineComments = listOf("%"),
            strings = listOf(StringRule("$$", multiline = true, escape = null), StringRule("$", escape = null)),
            annotationPrefix = '\\',
        ))
    })

    val BIBTEX = Language("bibtex", "BibTeX", "BIB", setOf("bib"), lexerFactory = {
        GenericLexer(LanguageSpec(
            lineComments = listOf("%"),
            strings = listOf(StringRule("\"")),
            annotationPrefix = '@', identExtra = "-",
        ))
    })

    val NGINX = Language("nginx", "nginx / Apache config", "CONF", setOf("nginx", "vhost"), setOf("nginx.conf", "httpd.conf", "apache2.conf", "caddyfile"), lexerFactory = {
        GenericLexer(LanguageSpec(
            keywords = words("""server location listen root index server_name proxy_pass include return rewrite try_files upstream http events
                ssl_certificate ssl_certificate_key access_log error_log add_header set if map alias VirtualHost Directory Location ServerName
                DocumentRoot RewriteEngine RewriteRule RewriteCond Require AllowOverride Options LoadModule Listen"""),
            literals = words("on off"),
            lineComments = listOf("#"),
            strings = listOf(StringRule("\""), StringRule("'")),
            variablePrefix = '$', identExtra = "_",
        ))
    })

    val DIFF = Language("diff", "Diff", "DIFF", setOf("diff", "patch", "rej"), lexerFactory = { DiffLexer })
    val LOG = Language("log", "Log", "LOG", setOf("log"), lexerFactory = { LogLexer })
    val PO = Language("po", "gettext", "PO", setOf("po", "pot"), lexerFactory = { PoLexer })
    val SUBTITLES = Language("subtitles", "Subtitles", "SUB", setOf("srt", "vtt"), lexerFactory = { SubtitleLexer })
    val RST = Language("rst", "reStructuredText", "RST", setOf("rst", "rest"), lexerFactory = { LightMarkupLexer("=-~^\"'`#*+", listOf(".. "), listOf(":")) })
    val ASCIIDOC = Language("asciidoc", "AsciiDoc", "ADOC", setOf("adoc", "asciidoc", "asc"), lexerFactory = { LightMarkupLexer("=", listOf("//"), listOf(":", "[")) })
    val ORG = Language("org", "Org", "ORG", setOf("org"), lexerFactory = { LightMarkupLexer("*", listOf("# "), listOf("#+")) })

    val JSON = Language("json", "JSON", "JSON", lexerFactory = { JsonLexer })
    val YAML = Language("yaml", "YAML", "YAML", setOf("yaml", "yml"), setOf(".clang-format", ".clang-tidy"), lexerFactory = { YamlLexer })
    val INI = Language(
        "ini", "INI / TOML", "INI", setOf("ini", "cfg", "conf", "toml", "properties", "editorconfig", "desktop", "service", "inf", "lock"),
        setOf(".editorconfig", ".gitconfig", ".gitmodules", ".npmrc", ".pylintrc", "gradle.properties", "local.properties", "cargo.lock", "pipfile"),
        lexerFactory = { IniLexer },
    )
    val XML = Language("xml", "XML", "XML", setOf("xml", "svg", "plist", "xsd", "xsl", "xslt", "xaml", "csproj", "fxml", "rss", "atom", "kml", "gpx", "wsdl", "pom"), lexerFactory = { MarkupLexer })
    val HTML = Language("html", "HTML", "HTML", setOf("html", "htm", "xhtml", "vue", "svelte", "jsp", "erb", "hbs"), lexerFactory = { MarkupLexer })
    val MARKDOWN = Language("markdown", "Markdown", "MD", lexerFactory = { MarkdownLexer })
    val RTF = Language("rtf", "RTF", "RTF", lexerFactory = { RtfLexer })
    val DOTENV = Language("dotenv", "dotenv", "ENV", lexerFactory = { DotenvLexer })
    val CSV = Language("csv", "CSV", "CSV", lexerFactory = { DelimitedLexer(',') })
    val TSV = Language("tsv", "TSV", "TSV", lexerFactory = { DelimitedLexer('\t') })
    val CSV_SEMICOLON = Language("csv-semicolon", "CSV", "CSV", lexerFactory = { DelimitedLexer(';') })

    val all: List<Language> = listOf(
        KOTLIN, JAVA, GROOVY, SCALA, C, CPP, CSHARP, GO, RUST, SWIFT, DART, JAVASCRIPT, TYPESCRIPT, PYTHON, RUBY,
        PHP, SHELL, POWERSHELL, SQL, LUA, R, PERL, CSS, DOCKERFILE, MAKEFILE, CMAKE, CONF, YAML, INI, XML, HTML,
        JULIA, HASKELL, ELIXIR, ERLANG, LISP, OCAML, FSHARP, ZIG, NIM, BATCH, GRAPHQL, PROTOBUF, HCL, NIX, LATEX, BIBTEX,
        NGINX, DIFF, LOG, PO, SUBTITLES, RST, ASCIIDOC, ORG,
    )

    private val byExt: Map<String, Language> by lazy { all.flatMap { l -> l.extensions.map { it to l } }.toMap() }
    private val byName: Map<String, Language> by lazy { all.flatMap { l -> l.fileNames.map { it to l } }.toMap() }

    fun byExtension(ext: String): Language? = byExt[ext.lowercase()]

    fun byFileName(lowerName: String): Language? =
        byName[lowerName] ?: when {
            lowerName.startsWith("dockerfile") || lowerName.startsWith("containerfile") -> DOCKERFILE
            lowerName.endsWith(".gradle.kts") -> KOTLIN
            else -> null
        }
}
