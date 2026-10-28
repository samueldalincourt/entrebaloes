# Validação no POCO M7 Pro

O modo padrão usa ML Kit offline, com Qwen/llama.cpp opcional. O POCO Android 15 está conectado por ADB. O leitor do app oficial bloqueou a captura (`FLAG_SECURE` confirmado). Com autorização do usuário, o alvo passou a ser o site oficial no Chrome. A leitura completa foi confirmada: 795 ms para três regiões no modo rápido (uma em cache, duas novas), além dos 550 ms de debounce. Consulte `IMPLEMENTATION_STATUS.md` para cargas, limitações e correções de qualidade.

1. Instale o APK novo com a biblioteca nativa incluída. Baixe o modelo e aguarde a integridade ser verificada. Registre Android, RAM total/disponível e diagnóstico do motor em Ajustes e diagnóstico.
2. Execute **Testar tradução no aparelho**. Registre a saída, tempo e memória. A primeira chamada inclui a inicialização se o modelo ainda não estiver em memória. Compare com uma segunda chamada para medir o modelo já carregado.
3. Autorize sobreposição/acessibilidade, inicie uma sessão e consinta a captura da tela inteira. Na versão 1.5.0 a sessão começa pausada. Abra o conteúdo e toque em Traduzir no controle flutuante; não há restrição de domínio. No modo online, os textos reconhecidos são enviados ao Gemini enquanto a leitura estiver liberada.
4. Confirme eventos de scroll, ocultação imediata e leitura após 550 ms sem novos eventos. Teste movimento lento, fling, retorno ao balão anterior e página totalmente parada. Pause durante uma tradução e confirme que resultados antigos não aparecem; retome e confira uma nova tradução. Arraste o controle pela alça e confirme que ele não aparece na imagem usada pelo OCR. Mudanças de conteúdo da página não devem cancelar a inferência em andamento.
5. Confirme captura sem os balões do próprio tradutor. Se um episódio resultar em captura preta/protegida, registre versão e episódio; não contorne a proteção.
6. Confira alinhamento, margens, textos curtos (“NO” e “I”), balões lado a lado e traduções longas. Teste gestos através do overlay no Android 12+.
7. Meça dez pausas com conteúdo novo e dez com frases em cache. Some 550 ms ao tempo do pipeline mostrado. Registre mediana e máximo; a meta inicial é cinco segundos, sujeita à quantidade de texto e ao aparelho.
8. Durante OCR/inferência, role, troque de app, bloqueie a tela e retire permissões. Resultados antigos não podem reaparecer. Rotação/troca de dimensões encerra a sessão. Confirme a ação Parar da notificação.
9. Pause/retome o download e teste interrupção de rede e rejeição de arquivo adulterado. Após concluir, teste tradução com rede desativada e episódio previamente disponível.
10. Faça uma leitura de 30 minutos com condições controladas de brilho/rede. Registre memória, temperatura e bateria. A sessão deve parar em aquecimento severo; o modelo é descarregado após 30 segundos fora do leitor.

Captura bloqueada, ausência de eventos de rolagem ou falha do JNI no aparelho continuam sendo bloqueios concretos de integração. A compatibilidade com OpenCL/GPU deixou de ser requisito do motor atual.
