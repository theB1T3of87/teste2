# FoxGirlSupremacy Native — auditoria e correções

## Alterações aplicadas

- SDK Cubism Native 5-r.5 movido para dentro da raiz do projeto Android. O repositório agora contém o SDK necessário para CMake e GitHub Actions.
- Caminho do SDK no `app/src/main/cpp/CMakeLists.txt` ajustado para a nova estrutura.
- CMake agora verifica, durante a configuração, a existência do arquivo Core correspondente à ABI e do `stb_image.h`.
- `gradlew` marcado como executável.
- Wrapper Gradle ajustado para baixar a distribuição `bin`, menor que a `all`.
- Workflow `.github/workflows/android-debug.yml` criado para compilar o APK Debug no GitHub Actions e publicar o APK como artifact.
- Construtor do delegate nativo corrigido para não chamar `glViewport` com largura/altura não inicializadas.
- Carregamento de texturas nativo protegido contra arquivo ausente ou PNG inválido.
- Carregamento do modelo nativo protegido contra falha ao abrir o `.model3.json` ou o `.moc3`.
- Destrutor do modelo protegido contra `_modelJson` nulo.
- Loop de renderização nativo protegido quando o modelo não foi carregado.
- Fila de comandos do renderer sincronizada com a criação/destruição do thread EGL para evitar perder comandos durante a inicialização.

## Verificações realizadas

- XML: todos os arquivos parseiam corretamente.
- JSON: todos os JSON dos assets parseiam corretamente.
- Modelo Hiyori: 17 referências de arquivos verificadas; nenhuma ausente.
- Cubism Core: arquivos estáticos presentes para `arm64-v8a` e `x86_64`.
- CMake: configure/generate no host passou após a mudança de estrutura.

## Bloqueios ainda existentes

Este ambiente não tem Android SDK/NDK e não consegue resolver `services.gradle.org`; portanto não foi possível executar `assembleDebug`, compilar as bibliotecas `.so` para Android, instalar ou testar o APK em um dispositivo.

**Nenhum APK foi criado ou incluído.** O workflow do GitHub Actions foi preparado para fazer a compilação real assim que este projeto for enviado a um repositório e o workflow for executado com sucesso.
