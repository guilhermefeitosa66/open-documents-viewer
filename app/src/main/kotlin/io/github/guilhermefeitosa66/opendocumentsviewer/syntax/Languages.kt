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

    val GROOVY = Language("groovy", "Groovy / Gradle", "GRDL", setOf("groovy", "gradle", "gvy"), lexerFactory = {
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

    val RUBY = Language("ruby", "Ruby", "RB", setOf("rb", "rake", "gemspec", "ru"), setOf("gemfile", "rakefile", "podfile", "fastfile", "vagrantfile"), lexerFactory = {
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
        setOf(".gitignore", ".dockerignore", ".npmignore", ".gitattributes", ".htaccess", "codeowners", "requirements.txt", ".nvmrc", ".tool-versions"),
        lexerFactory = {
            GenericLexer(LanguageSpec(lineComments = listOf("#"), strings = listOf(StringRule("\""))))
        },
    )

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
    val DOTENV = Language("dotenv", "dotenv", "ENV", lexerFactory = { DotenvLexer })
    val CSV = Language("csv", "CSV", "CSV", lexerFactory = { DelimitedLexer(',') })
    val TSV = Language("tsv", "TSV", "TSV", lexerFactory = { DelimitedLexer('\t') })
    val CSV_SEMICOLON = Language("csv-semicolon", "CSV", "CSV", lexerFactory = { DelimitedLexer(';') })

    val all: List<Language> = listOf(
        KOTLIN, JAVA, GROOVY, SCALA, C, CPP, CSHARP, GO, RUST, SWIFT, DART, JAVASCRIPT, TYPESCRIPT, PYTHON, RUBY,
        PHP, SHELL, POWERSHELL, SQL, LUA, R, PERL, CSS, DOCKERFILE, MAKEFILE, CMAKE, CONF, YAML, INI, XML, HTML,
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
