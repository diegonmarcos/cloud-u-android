import 'package:GitSync/constant/langLog.dart';
import 'package:re_highlight/languages/dart.dart';
import 'package:re_highlight/languages/javascript.dart';
import 'package:re_highlight/languages/typescript.dart';
import 'package:re_highlight/languages/json.dart';
import 'package:re_highlight/languages/python.dart';
import 'package:re_highlight/languages/java.dart';
import 'package:re_highlight/languages/cpp.dart';
import 'package:re_highlight/languages/csharp.dart';
import 'package:re_highlight/languages/go.dart';
import 'package:re_highlight/languages/ruby.dart';
import 'package:re_highlight/languages/php.dart';
import 'package:re_highlight/languages/xml.dart';
import 'package:re_highlight/languages/css.dart';
import 'package:re_highlight/languages/markdown.dart';
import 'package:re_highlight/languages/yaml.dart';
import 'package:re_highlight/languages/sql.dart';
import 'package:re_highlight/languages/bash.dart';
import 'package:re_highlight/languages/swift.dart';
import 'package:re_highlight/languages/kotlin.dart';
import 'package:re_highlight/languages/objectivec.dart';
import 'package:re_highlight/languages/scss.dart';
import 'package:re_highlight/languages/less.dart';
import 'package:re_highlight/languages/perl.dart';
import 'package:re_highlight/languages/rust.dart';
import 'package:re_highlight/languages/lua.dart';
import 'package:re_highlight/languages/shell.dart';
import 'package:re_highlight/languages/scala.dart';
import 'package:re_highlight/languages/haskell.dart';
import 'package:re_highlight/languages/clojure.dart';
import 'package:re_highlight/languages/elixir.dart';
import 'package:re_highlight/languages/erlang.dart';
import 'package:re_highlight/languages/powershell.dart';
import 'package:re_highlight/languages/makefile.dart';
import 'package:re_highlight/languages/prolog.dart';
import 'package:re_highlight/languages/r.dart';
import 'package:re_highlight/languages/tcl.dart';
import 'package:re_highlight/languages/vbnet.dart';
import 'package:re_highlight/languages/plaintext.dart';

final extensionToLanguageMap = {
  'dart': {'dart': langDart},
  'js': {'html': langXml, 'css': langCss, 'javascript': langJavascript},
  'mjs': {'javascript': langJavascript},
  'ts': {'html': langXml, 'css': langCss, 'javascript': langTypescript},
  'json': {'json': langJson},
  'py': {'python': langPython},
  'java': {'java': langJava},
  'cpp': {'cpp': langCpp},
  'cc': {'cpp': langCpp},
  'cxx': {'cpp': langCpp},
  'cs': {'csharp': langCsharp},
  'go': {'go': langGo},
  'rb': {'ruby': langRuby},
  'php': {'php': langPhp},
  'html': {'html': langXml, 'css': langCss, 'javascript': langJavascript},
  'htm': {'html': langXml, 'css': langCss, 'javascript': langJavascript},
  'xml': {'xml': langXml},
  'css': {'css': langCss},
  'md': {'markdown': langMarkdown},
  'markdown': {'markdown': langMarkdown},
  'yml': {'yaml': langYaml},
  'yaml': {'yaml': langYaml},
  'sql': {'sql': langSql},
  'sh': {'bash': langBash},
  'bash': {'bash': langBash},
  'swift': {'swift': langSwift},
  'kt': {'kotlin': langKotlin},
  'kts': {'kotlin': langKotlin},
  'm': {'objectivec': langObjectivec},
  'mm': {'objectivec': langObjectivec},
  'scss': {'scss': langScss},
  'less': {'less': langLess},
  'pl': {'perl': langPerl},
  'pm': {'perl': langPerl},
  'rs': {'rust': langRust},
  'lua': {'lua': langLua},
  'zsh': {'shell': langShell},
  'c': {'cpp': langCpp},
  'h': {'cpp': langCpp},
  'scala': {'scala': langScala},
  'hs': {'haskell': langHaskell},
  'lhs': {'haskell': langHaskell},
  'clj': {'clojure': langClojure},
  'cljs': {'clojure': langClojure},
  'ex': {'elixir': langElixir},
  'exs': {'elixir': langElixir},
  'erl': {'erlang': langErlang},
  'ps1': {'powershell': langPowershell},
  'psm1': {'powershell': langPowershell},
  'makefile': {'makefile': langMakefile},
  'mk': {'makefile': langMakefile},
  'pro': {'prolog': langProlog},
  'log': {'log': langLog},
  'r': {'r': langR},
  'tcl': {'tcl': langTcl},
  'vb': {'vbnet': langVbnet},
  'txt': {'plaintext': langPlaintext},
};
