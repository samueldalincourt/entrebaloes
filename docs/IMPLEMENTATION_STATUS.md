# Resultado da implementação — 27/10/2026

O modo padrão é tradução offline dedicada por ML Kit, com Qwen2.5 1.5B Instruct GGUF Q4_K_M/llama.cpp mantido como opção. O usuário autorizou trocar motores para obter um app funcional. O histórico do MLC está em `legacy-mlc/`.

## Verificado

- APK release ARM64 compilado com a biblioteca JNI real incluída e instalado no POCO por USB.
- 14 testes Flutter aprovados, incluindo cache persistente separado entre motores, descarte de resultados após rolagem e agrupamento de linhas divididas entre blocos do ML Kit.
- 5 testes Kotlin aprovados: 2 de debounce e 3 de normalização/preservação de nomes.
- Modelo oficial verificado por SHA-256 e tradução de quatro balões executada no computador (`../dist/translation-quality-1.5b.json`).
- No POCO Android 15, o botão de benchmark retornou “Vou ser o rei dos piratas!” em 5.490 ms, incluindo preparação. Isso comprova inferência JNI de uma frase no aparelho, não a latência completa do leitor.
- Acessibilidade conectada e MediaProjection ativa foram confirmadas por ADB.

## Investigação em andamento

O build 1.1.1+3 confirmou 28 eventos de scroll, alvo visível, overlay pronto e erro de captura preta. A janela `WebtoonViewerActivity` tem `SECURE` nos atributos do Android; uma captura comum do Android também ficou preta. Portanto, a ausência dos balões nessa tentativa ocorre antes do OCR, por proteção de captura do leitor oficial.

O usuário autorizou a mudança para o site oficial no Chrome. A captura comum da página no POCO mostrou balões em inglês normalmente. O build 1.2.0+4 altera o alvo para `com.android.chrome`, atualiza onboarding e inclui aviso antes de cada sessão: a aba visível do Chrome pode ser processada e a sessão deve ser parada antes de visitar outras páginas. O app não verifica o domínio da aba. Não há contorno de `FLAG_SECURE`.

Evitar `uiautomator dump` durante a sessão: a conexão UiAutomation padrão suspende outros serviços de acessibilidade e o watchdog encerra a captura. Usar logs `WebtoonPipeline`, dump do serviço e, quando necessário, screenshot comum do Android. Não contornar conteúdo protegido.

## Leitura real no Chrome

O build 1.2.0+4 completou captura → OCR → Qwen → overlay no POCO, confirmado pelos logs, screenshot e pelo usuário. Foram detectadas seis regiões, processadas em dois lotes (4 + 2). O tempo completo foi 29.310 ms, além do debounce de 550 ms; as primeiras quatro traduções apareceram por volta de 18 segundos. A meta de cinco segundos não foi atingida. A screenshot mostrou um diálogo dividido em várias regiões e texto original parcialmente descoberto.

## Otimização 1.2.1+5

- CPU recompilada com `-march=armv8.2-a+dotprod+fp16`, confirmado no comando real do compilador. Todos os núcleos do POCO anunciaram essas capacidades em `/proc/cpuinfo`. O wrapper JNI verifica HWCAP antes de disponibilizar o motor, retornando erro em aparelhos sem suporte.
- Recorte padrão para o Chrome reduzido de 10% em cada margem para 5% no topo e 3% na base; margens ajustadas manualmente continuam preservadas.
- Agrupamento considera linhas alinhadas de blocos diferentes com critérios mais estritos; linhas de colunas vizinhas e regiões com tamanhos de fonte muito diferentes permanecem separadas.
- Logs internos de nível debug do llama.cpp não são mais encaminhados ao logcat.
- Build release compilado e instalado. Tentativa com três regiões (uma em cache, duas novas) completou em 9.287 ms. A carga difere da medição anterior, portanto não é um benchmark controlado da CPU.

O APK auxiliar de testes instrumentados foi recusado pelo Android (`INSTALL_FAILED_USER_RESTRICTED`); esse teste não foi executado. O app principal atualizado instalou normalmente. A validação de desempenho usa os indicadores do próprio app e leitura real.

## Modo rápido 1.3.1+7

Google Translate offline via ML Kit 17.0.3 foi integrado como modo padrão. O download usa Wi-Fi, e a inferência usa o SDK no aparelho, sem uma API remota de tradução. Qwen continua selecionável; a troca é bloqueada durante uma sessão. Os caches são separados por motor. O modelo rápido oferece `pt`, sem variante regional configurável; a UI informa essa limitação.

O teste inicial real no POCO produziu português correto em 1.199 ms. Uma página no Chrome completou em 795 ms, com três regiões, uma em cache e duas novas. Somados aos 550 ms de debounce, são aproximadamente 1,35 s nessa amostra. OCR, tradução e overlay foram confirmados por dump e screenshot. Essa amostra atinge a meta inicial de cinco segundos, sem estabelecer um SLA geral.

O WindowManager estava ajustando o overlay para 1080×2355, enquanto a captura tinha 1080×2400. O ajuste de insets do overlay foi desativado no Android 11+, alinhando as coordenadas com a captura completa.

## Correção de qualidade 1.3.2+8

No episódio testado, o OCR entregou uma frase inteira iniciada por `.AMONG` em maiúsculas. O tradutor deixou esse termo em inglês, e `NEST` virou substantivo comum. A correção separa as reticências iniciais, normaliza frases totalmente em maiúsculas e protege os termos do glossário por marcadores com validação de retorno. `Nest` é o termo inicial do teste e pode ser editado nos ajustes. A barra de endereços também estava sendo reconhecida e traduzida; linhas contendo URLs são descartadas.

O namespace do cache rápido foi atualizado e inclui o SHA-256 do glossário, para não reutilizar as respostas anteriores à correção ou a termos diferentes. O teste automático do SDK inclui reticências e continuação de diálogo, além da frase original de benchmark. O OCR detalhado permanece apenas em RAM e só aparece ao solicitar `dumpsys activity service ... --ocr`; não vai para os logs comuns e é limpo ao encerrar o serviço.

A versão corrigida compilou, passou nos 14 testes Flutter, nos 5 testes Kotlin e na análise estática, e foi instalada no POCO. O teste automático real do SDK com três frases passou em 2.394 ms: a frase com reticências contém “entre”, não mantém “among” e preserva “Nest”. Uma nova autorização de captura é necessária para revisar o mesmo balão depois da atualização.

A qualidade em outras páginas, retorno ao cache, temperatura e consumo em leitura prolongada continuam em validação.
