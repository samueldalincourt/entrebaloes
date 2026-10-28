# Compilar o tradutor Android

O motor padrão é **Google Translate offline via ML Kit**, dependência Android `com.google.mlkit:translate:17.0.3`. Os pesos de idioma são preparados no aparelho pelo SDK com Wi-Fi; a tradução executa localmente. O SDK oferece `pt`, sem seleção regional específica.

O motor opcional é **llama.cpp em CPU**, integrado por JNI e compilado pelo Android NDK junto com o app. A versão 1.6.0 usa **Hy-MT2 1.8B GGUF Q4_K_M**, especializado em tradução, com tarefa para PT-BR. O modo LLM executa offline; o modo Online usa o Gemini separadamente.

## Ambiente

- Flutter 3.41.9 / Dart 3.11.5 ou versão compatível com `pubspec.lock`.
- JDK do Android Studio, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1.
- Android 8.0+ (API 26), dispositivo ARM64. O alvo de validação é o POCO M7 Pro.

```powershell
. ./scripts/android-env.ps1
./scripts/prepare_llama.ps1
flutter pub get
flutter analyze
flutter test
flutter build apk --release --target-platform android-arm64
cd android
./gradlew.bat :app:testDebugUnitTest
```

O script baixa o fonte oficial do llama.cpp e fixa o commit `7ecb57f7adbea2928aa468ad2aecf05b10725ad4`. O CMake verifica esse commit e inclui o motor diretamente no APK, sem artefatos opcionais. Até o APK debug compila o núcleo de inferência com otimizações. Não são necessários MLC, TVM, Rust ou um compilador de modelos.

Para instalar, use `adb install -r build/app/outputs/flutter-apk/app-release.apk`. O release local usa a chave de debug para permitir atualizar a instalação de desenvolvimento; configure uma chave própria antes de distribuir publicamente.

## Modelo e integridade

O build 1.2.1+5 é otimizado para o POCO testado: `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`. O wrapper JNI permanece ARM64 básico e recusa CPUs sem DOTPROD/FP16 por HWCAP. Essa versão não é um binário ARM64 universal; outros aparelhos precisam ter essas instruções ou de outro perfil de compilação.

O APK contém um manifesto de download com a revisão fixa `a0c709d9fac510f2c807aa3af52872340dc37a4a` do repositório oficial `tencent/Hy-MT2-1.8B-GGUF`.

- Arquivo: `Hy-MT2-1.8B-Q4_K_M.gguf`.
- Tamanho: 1.133.080.448 bytes (aproximadamente 1,13 GB).
- SHA-256: `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`.

O download no celular pode ser pausado e retomado. O app só marca o modelo como pronto depois de verificar o arquivo inteiro. O modelo é lido diretamente em GGUF, com mapeamento de memória, contexto de 2048 tokens, lote de prefill de 128 e duas threads. O threadpool usa os núcleos de maior capacidade, quando detectáveis. A inicialização e o aquecimento do prompt ocorrem antes do consentimento de captura, fora da thread principal. Pesos em `getExternalFilesDir()/model-import/` podem ser importados, sempre com verificação de SHA-256.

O fluxo LLM traduz uma fala por vez, em texto simples; Kotlin monta o JSON com os IDs originais e preserva o glossário. O Dart valida os IDs, exibe cada balão pronto e descarta resultados se a tela mudar. O prefixo do prompt é reutilizado com limpeza do restante do contexto anterior. Cada geração tem cancelamento e limite de 60 segundos. A gramática JSON permanece disponível no JNI para chamadores de compatibilidade.

## Teste real no computador

O teste de desktop usa o binário CPU oficial `llama.cpp b11669` disponível em `.tools/llama-desktop/`. Ele valida pesos e traduções, sem medir o desempenho do Android. As medições reais no celular estão em [LOCAL_PERFORMANCE.md](LOCAL_PERFORMANCE.md).

```powershell
python scripts/fetch_model.py
python scripts/verify_translation.py
```

O segundo comando espera o binário oficial extraído em `.tools/llama-desktop/`. Para Hy-MT, ele usa a instrução real do app e traduz quatro falas separadamente, verificando negação, expressões idiomáticas e preservação do nome Luffy. O relatório fica em `dist/translation-smoke.json`.

Esse teste comprova a inferência do modelo no computador. A captura do WEBTOON, a execução do JNI e a meta de cinco segundos no POCO exigem validação no aparelho físico.

## Histórico do bloqueio MLC

A tentativa anterior falhou no passe TVM `AttachGlobalSymbol` ao converter uma expressão de tensor em expressão escalar. Com autorização do usuário para trocar motor e modelo, o caminho foi substituído pelo llama.cpp. Fontes e registro da tentativa anterior estão em `docs/legacy-mlc/`; não fazem parte do runtime atual.

Referências: [llama.cpp Android](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md), [modelo oficial Hy-MT2 GGUF](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), [MediaProjection](https://developer.android.com/media/grow/media-projection).
