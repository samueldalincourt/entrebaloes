# Otimização offline — 28/10/2026

A versão 1.6.0 seleciona **Hy-MT2 1.8B Q4_K_M** para o modo LLM. Qwen2.5 e Qwen3 foram medidos no POCO, mas continuaram apresentando erros importantes em expressões idiomáticas, inclusive após melhorar a velocidade.

## Evidência e alcance

As [medições completas](benchmarks/offline-2026-10-28.json) contêm entradas e saídas sintéticas, configuração, tempos de prefill/geração e cancelamento. Foram executadas no próprio POCO M7 Pro, com os mesmos pesos e o mesmo código C++ utilizado pelo JNI. Não são estimativas de desktop nem medições da API online.

| Configuração | Mediana por balão | Observação |
|---|---:|---|
| Qwen2.5 anterior, JSON, 3 threads, sem cache de prompt | 4,791 s | 6 falas, 2 repetições; erros de sentido |
| Qwen2.5, JSON, cache, 2 threads | 3,013 s | Ganho real; qualidade ainda insuficiente |
| Qwen2.5, texto direto, cache, 2 threads | 1,644 s | Rápido, mas ainda errou expressões |
| Hy-MT2 selecionado, texto direto, cache, núcleos fortes | 1,602 s | Mesmas 6 falas, 2 repetições |

No conjunto selecionado, as 12 execuções ficaram entre **1,247 e 2,129 s**. Outras 9 entradas, sem respostas fornecidas no prompt, ficaram entre **1,237 e 2,465 s**. São tempos do motor: captura, OCR, desenho e os 550 ms de espera após rolagem são adicionais. A meta de 3 segundos não é garantida para balões longos, aquecimento, multitarefa ou uma tela inteira.

A preparação inicial é separada da leitura: carregar os pesos e preparar o prefixo ocorre antes do consentimento de captura. O benchmark do app informa preparação e tradução separadamente. As traduções aparecem por balão, sem aguardar a conclusão dos quatro itens de um lote.

## Validação completa no app

O usuário confirmou que a versão 1.6.0 ficou mais rápida. Os logs do processo confirmaram `mode=llm`, motor JNI local, desenho progressivo e ausência de erro na sessão. Um balão curto foi da tela estável ao desenho em aproximadamente **2,0 s** (cerca de 0,85 s de inferência). Outra tela apresentou o primeiro balão em aproximadamente **3,3 s**. Falas mais longas levaram cerca de **4,0–5,4 s só no modelo**; a primeira tela também teve custo extra de captura/OCR. Logo, o ganho é real e a meta prática foi atingida para falas curtas, mas **não para todo tamanho de balão**. O benchmark sintético não substitui essas medições completas.

## Mudanças mantidas

- Duas threads e um threadpool vinculado aos núcleos com maior `cpu_capacity`, quando essa informação está disponível. Não altera frequência nem limites térmicos.
- Reuso do prefixo exato de tokens, removendo o restante do KV anterior. Erros e cancelamentos descartam o estado não confiável. Repetições reavaliam o último token para atualizar os logits.
- Prefixo aquecido antes da primeira leitura.
- Geração de texto simples para um balão; Kotlin mantém os IDs e monta o JSON. A gramática JSON continua disponível para outros chamadores e pode encerrar assim que o objeto válido termina.
- Marcadores reversíveis preservam os nomes configurados no glossário. Cache de traduções separado pelo modelo, versão e glossário.
- Template oficial Hy-MT, com a tarefa dentro da mensagem do usuário. O formatter genérico Hunyuan-VL do snapshot de llama.cpp usa outra ordem de marcadores e não foi utilizado para esse modelo.
- Importação opcional de pesos já copiados para `getExternalFilesDir()/model-import/`, validando SHA-256 antes e depois da cópia. O download HTTPS segue disponível para instalações novas.

## Qualidade

O Hy-MT2 corrigiu falhas graves observadas nos Qwen: “Keep an eye on the entrance” virou “Fique de olho na entrada”; “don't let your guard down” virou “não baixe a guarda”. Foram conferidos negação, nomes, fragmentos, sarcasmo e expressões diferentes das usadas no ajuste. Algumas construções ainda podem ficar menos naturais ou perder nuance. A amostra pequena não comprova qualidade equivalente ao Gemini em capítulos completos.

O modelo foi publicado pela Tencent sob [Apache 2.0](../assets/attribution/LICENSE-Hy-MT2.txt), com pesos, revisão e checksum no manifesto. A versão HY-MT1.5 foi avaliada, mas não é a selecionada.

## GPU: não ativada no APK

O driver físico PowerVR é Vulkan 1.1, embora o loader Android anuncie 1.3. O backend sem mudanças rejeitou os 16 KB de memória compartilhada. Experimentos isolados progrediram, mas falharam em chamadas Vulkan 1.2 indisponíveis (`getBufferAddress`, `waitSemaphores`), inclusive com modo sem transferência assíncrona. Não houve geração de tokens por GPU. Os scripts `vulkan-build-bench.ps1` e `vulkan-patch-bench.py` documentam o experimento; não participam do build normal nem alteram a cópia fixada do llama.cpp.

## Reproduzir

O alvo CMake `webtoon_offline_bench` é explícito, excluído do build padrão e não entra no APK. Ele executa o mesmo motor sem precisar instalar um APK de testes no aparelho. Opções: `--threads`, `--cache`, `--early-stop`, `--plain`, `--pin`, `--prime`, `--held-out`, `--rounds` e `--cancel-check`. A comparação de referência usa `--threads 3 --cache 0 --early-stop 0`; a selecionada usa `--threads 2 --cache 1 --early-stop 1 --plain --pin --prime`. Os testes de cancelamento confirmaram interrupção em cerca de 0,1 s e retomada com saída válida.

`scripts/summarize_offline_bench.py` gera o arquivo de evidências a partir dos JSONL em `dist/`. `scripts/test_json_completion.cpp` valida o detector incremental de término de JSON em ambiente host.
