# Integração Cubism Native 5 R5

Esta versão substitui a dependência do Cubism Java por uma integração JNI com o **Live2D Cubism SDK for Native 5 R5** enviado nesta conversa.

## O que foi conectado

- Framework C++ oficial e Core Android (`arm64-v8a` e `x86_64`).
- Renderer OpenGL ES 2 do SDK, hospedado em um `TextureView` transparente.
- JNI para inicialização, ciclo de vida, renderização, motions, expressions e leitura de assets/arquivos.
- Modelo selecionado pelo app: tanto o modelo Hiyori em `assets/live2d/hiyori/` quanto modelos importados no armazenamento privado são resolvidos pelo mesmo callback Java.
- CameraX + ML Kit continuam processando os frames localmente; os valores faciais chegam ao modelo nativo e atualizam `ParamAngleX/Y/Z`, `ParamEyeLOpen`, `ParamEyeROpen`, `ParamEyeBallX/Y` e `ParamMouthForm`.
- Fundo do framebuffer limpo com alpha zero para manter a transparência do overlay.

## Compilar

Instale JDK 17+, Android SDK Platform 35, NDK `26.3.11579264` e CMake `3.22.1`. Na raiz do projeto:

```bash
./gradlew assembleDebug
```

O APK debug deverá aparecer em `app/build/outputs/apk/debug/app-debug.apk`.

## Estado de validação desta entrega

A integração de código e a configuração CMake/Gradle foram preparadas. Não foi possível compilar o APK neste ambiente porque o Gradle 8.14.3 não está disponível em cache e `services.gradle.org` não pôde ser acessado. Também não há Android SDK/NDK instalado no ambiente de empacotamento. Portanto, esta entrega **não afirma que o APK foi compilado ou testado em aparelho**. A compilação final e o teste de câmera/renderer precisam ser feitos em Android Studio com o toolchain acima.

O SDK Live2D mantém os seus próprios termos e avisos em `CubismSdkForNative-5-r.5/LICENSE.md` e `NOTICE.md`. Mantenha esses arquivos junto do SDK e respeite a licença aplicável.
