//> using scala 3.8.3
//> using dep ch.epfl.lamp::steps::0.2.1
// -Yretain-trees: AnnotMirror reads annotation default-argument getters' trees to
// mirror their constants; without it, same-project cross-file defaults are invisible
// (TASTy from published dependencies always has them).
//> using options -deprecation -feature -unchecked -Yretain-trees
//> using test.dep org.scalameta::munit::1.1.1
