# Handoff — Cubism Native

O SDK Cubism Native 5-r.5 está incluído dentro da raiz deste repositório em
`CubismSdkForNative-5-r.5/`. O projeto Android usa somente o Framework C++ e as
bibliotecas Core estáticas do Native via CMake/NDK/JNI; não há dependência do
módulo Cubism SDK for Java.

Consulte `NATIVE_MIGRATION.md` para a estrutura, requisitos de build e estado de
validação. A compilação completa deve ser confirmada pelo workflow
`.github/workflows/android-debug.yml` ou em um ambiente com Android SDK/NDK.
