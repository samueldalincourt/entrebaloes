import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../platform/native_bridge.dart';
import 'online_settings.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});
  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final bridge = const NativeBridge();
  Map<Object?, Object?> state = {};
  StreamSubscription<Map<Object?, Object?>>? subscription;
  Timer? refreshTimer;
  bool busy = false;
  String? message;
  final glossary = TextEditingController();
  bool glossaryLoaded = false;
  double top = .05, bottom = .03;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    subscription = bridge.events.listen(
      (event) {
        if (event['type'] == 'download') {
          if (mounted) {
            setState(() {
              state['downloadProgress'] = event['progress'];
            });
          }
        } else {
          unawaited(refresh());
        }
      },
      onError: (Object error) {
        if (mounted) {
          setState(() {
            message = error.toString();
          });
        }
      },
    );
    unawaited(refresh());
    refreshTimer = Timer.periodic(const Duration(seconds: 3), (_) => refresh());
  }

  Future<void> refresh() async {
    try {
      final next = await bridge.status();
      if (mounted) {
        setState(() {
          state = next;
          if (!glossaryLoaded) {
            glossary.text = next['glossary'] as String? ?? 'Nest';
            glossaryLoaded = true;
          }
          top = (next['topCrop'] as num?)?.toDouble() ?? .05;
          bottom = (next['bottomCrop'] as num?)?.toDouble() ?? .03;
        });
      }
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          message = error.message;
        });
      }
    } on MissingPluginException {
      if (mounted) {
        setState(() {
          message =
              'Este app precisa do Android para capturar e traduzir a tela.';
        });
      }
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(refresh());
    }
  }

  Future<void> action(String command, [Map<String, Object?>? arguments]) async {
    setState(() {
      busy = true;
      message = null;
    });
    try {
      await bridge.command(command, arguments);
      await refresh();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          message = error.message;
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
        });
      }
    }
  }

  Future<void> diagnostics() async {
    try {
      const channel = MethodChannel('tradutor/commands');
      final report = await channel.invokeMapMethod<String, Object?>(
        'diagnostics',
      );
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Diagnóstico do aparelho'),
          content: SingleChildScrollView(
            child: SelectableText(
              const JsonEncoder.withIndent('  ').convert(report),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Fechar'),
            ),
          ],
        ),
      );
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          message = error.message;
        });
      }
    }
  }

  @override
  void dispose() {
    glossary.dispose();
    refreshTimer?.cancel();
    unawaited(subscription?.cancel());
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  bool enabled(String key) => state[key] == true;
  @override
  Widget build(BuildContext context) {
    final ready =
        enabled('overlayPermission') &&
        enabled('accessibility') &&
        enabled('runtimeAvailable') &&
        enabled('modelReady');
    final running = enabled('running');
    final mode = state['translationMode'] as String? ?? 'fast';
    final fastMode = mode == 'fast';
    final onlineMode = mode == 'online';
    final progress = (state['downloadProgress'] as num?)?.toDouble() ?? 0;
    final metrics = state['metrics'] as Map?;
    final error = message ?? state['lastError'] as String?;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 620),
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                Row(
                  children: [
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: const Color(0xffbff6dc),
                        borderRadius: BorderRadius.circular(14),
                      ),
                      child: const Icon(
                        Icons.translate_rounded,
                        color: Color(0xff173b2e),
                      ),
                    ),
                    const SizedBox(width: 12),
                    const Expanded(
                      child: Text(
                        'ENTRE BALÕES',
                        style: TextStyle(
                          fontWeight: FontWeight.w800,
                          letterSpacing: 2,
                          fontSize: 14,
                        ),
                      ),
                    ),
                    Icon(
                      onlineMode
                          ? Icons.cloud_rounded
                          : Icons.offline_bolt_rounded,
                      size: 18,
                      color: Color(0xffbff6dc),
                    ),
                    const SizedBox(width: 4),
                    Text(
                      onlineMode ? 'ONLINE' : 'LOCAL',
                      style: const TextStyle(fontSize: 11, letterSpacing: 1.5),
                    ),
                  ],
                ),
                const SizedBox(height: 32),
                const Text(
                  'Sua história.\nNo seu idioma.',
                  style: TextStyle(
                    fontSize: 38,
                    fontWeight: FontWeight.w800,
                    height: 1.12,
                    letterSpacing: -1.4,
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Abra seu conteúdo e toque em Traduzir no controle flutuante. Ao parar a rolagem, os balões ganham tradução em português.',
                  style: TextStyle(
                    color: Color(0xffaeb9c8),
                    fontSize: 16,
                    height: 1.5,
                  ),
                ),
                const SizedBox(height: 18),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _tag(fastMode ? 'EN → PT' : 'EN → PT-BR'),
                    _tag(onlineMode ? 'IA ONLINE' : 'TRADUÇÃO OFFLINE'),
                    _tag('CACHE NO CELULAR'),
                  ],
                ),
                const SizedBox(height: 28),
                _card(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _section('01', 'Prepare a leitura'),
                      const SizedBox(height: 8),
                      _permission(
                        'Sobreposição',
                        'Mostra o português sobre os balões.',
                        enabled('overlayPermission'),
                        () => action('openOverlaySettings'),
                      ),
                      const Divider(color: Color(0xff2d3849)),
                      _permission(
                        'Acessibilidade',
                        'Detecta rolagem e troca de aplicativo.',
                        enabled('accessibility'),
                        () => _accessibilityDisclosure(),
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        'Você controla a leitura pelo botão flutuante: Traduzir libera o processamento da tela visível; Pausar interrompe e esconde as traduções. Arraste pela alça para mudar a posição. Conteúdos protegidos pelo Android podem impedir a captura.',
                        style: TextStyle(color: Color(0xffbff6dc), height: 1.5),
                      ),
                      const SizedBox(height: 14),
                      const Text(
                        'A captura é autorizada pelo Android a cada nova sessão. A notificação permite parar a qualquer momento.',
                        style: TextStyle(
                          color: Color(0xffaeb9c8),
                          height: 1.5,
                          fontSize: 12,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 16),
                _card(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _section('02', 'Tradução'),
                      const SizedBox(height: 16),
                      SegmentedButton<String>(
                        segments: const [
                          ButtonSegment(value: 'fast', label: Text('Rápido')),
                          ButtonSegment(value: 'llm', label: Text('LLM')),
                          ButtonSegment(value: 'online', label: Text('Online')),
                        ],
                        selected: {mode},
                        onSelectionChanged: busy || running
                            ? null
                            : (selected) => _selectMode(selected.single),
                      ),
                      const SizedBox(height: 16),
                      if (onlineMode)
                        OnlineSettings(
                          state: state,
                          busy: busy,
                          running: running,
                          action: action,
                        ),
                      if (!onlineMode) ...[
                        Text(
                          fastMode
                              ? 'ML Kit · Tradução no aparelho'
                              : 'Hy-MT2 · IA de tradução offline',
                          style: const TextStyle(
                            fontWeight: FontWeight.w700,
                            fontSize: 17,
                          ),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          enabled('modelReady')
                              ? fastMode
                                    ? 'Modelo pronto. Tradução offline para português (pt); sem configuração regional específica de PT-BR.'
                                    : 'Modelo verificado e pronto neste aparelho.'
                              : fastMode
                              ? 'Modelo de tradução de cerca de 30 MB. A preparação usa Wi-Fi; depois, a tradução funciona offline.'
                              : 'Modelo de aproximadamente 1,13 GB. Depois da preparação inicial, as traduções funcionam sem internet.',
                          style: const TextStyle(
                            color: Color(0xffaeb9c8),
                            height: 1.5,
                          ),
                        ),
                        if (enabled('downloadRunning')) ...[
                          const SizedBox(height: 16),
                          LinearProgressIndicator(
                            value: fastMode ? null : progress,
                          ),
                          const SizedBox(height: 8),
                          Text(
                            fastMode
                                ? 'Preparando o modelo rápido pelo Wi-Fi…'
                                : '${(progress * 100).toStringAsFixed(1)}% · Você pode pausar e retomar.',
                          ),
                        ],
                        const SizedBox(height: 14),
                        if (!enabled('modelReady'))
                          OutlinedButton.icon(
                            onPressed:
                                busy || (fastMode && enabled('downloadRunning'))
                                ? null
                                : () => action(
                                    !fastMode && enabled('downloadRunning')
                                        ? 'pauseDownload'
                                        : 'downloadModel',
                                  ),
                            icon: Icon(
                              !fastMode && enabled('downloadRunning')
                                  ? Icons.pause
                                  : Icons.download_rounded,
                            ),
                            label: Text(
                              !fastMode && enabled('downloadRunning')
                                  ? 'Pausar download'
                                  : fastMode
                                  ? 'Preparar modelo rápido'
                                  : 'Baixar / retomar modelo',
                            ),
                          ),
                        if (!enabled('runtimeAvailable'))
                          const Padding(
                            padding: EdgeInsets.only(top: 10),
                            child: Text(
                              'O motor local não carregou. Consulte o diagnóstico ou reinstale o APK completo.',
                              style: TextStyle(
                                color: Color(0xffffc4a4),
                                height: 1.5,
                              ),
                            ),
                          ),
                        if (enabled('runtimeAvailable') &&
                            enabled('modelReady'))
                          TextButton.icon(
                            onPressed: busy || running
                                ? null
                                : () => action('benchmark'),
                            icon: const Icon(Icons.speed_rounded),
                            label: const Text('Testar tradução no aparelho'),
                          ),
                        if ((state['benchmark'] as Map?)?.isNotEmpty == true)
                          Text(
                            '${(state['benchmark'] as Map)['translation']}\n'
                            '${(state['benchmark'] as Map)['elapsedMs']} ms de tradução'
                            '${(state['benchmark'] as Map)['preparationMs'] != null ? ' · ${(state['benchmark'] as Map)['preparationMs']} ms de preparação' : ', incluindo preparação.'}',
                            style: const TextStyle(
                              color: Color(0xffbff6dc),
                              height: 1.5,
                            ),
                          ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  onPressed: busy || (!running && !ready)
                      ? null
                      : () => running ? action('stopSession') : _startReading(),
                  style: FilledButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 18),
                    backgroundColor: running
                        ? const Color(0xffffc4a4)
                        : const Color(0xffbff6dc),
                    foregroundColor: const Color(0xff10251d),
                  ),
                  icon: busy
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : Icon(
                          running
                              ? Icons.stop_rounded
                              : Icons.play_arrow_rounded,
                        ),
                  label: Text(
                    running
                        ? 'Parar tradução'
                        : onlineMode
                        ? 'Iniciar tradução online'
                        : fastMode
                        ? 'Traduzir com Google (offline)'
                        : 'Iniciar sessão de leitura',
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                ),
                if (running) ...[
                  const SizedBox(height: 8),
                  Text(
                    state['pipelineStage'] as String? ?? 'Preparando leitura.',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Color(0xffbff6dc)),
                  ),
                  TextButton.icon(
                    onPressed: () => action('openWebtoon'),
                    icon: const Icon(Icons.open_in_new),
                    label: Text('Abrir WEBTOON (opcional)'),
                  ),
                  Text(
                    enabled('readingPaused')
                        ? 'Toque em Traduzir no controle flutuante quando estiver no conteúdo desejado.'
                        : 'Use Pausar no controle flutuante para interromper a tradução.',
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: Color(0xffaeb9c8)),
                  ),
                ],
                if (error != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 18),
                    child: Text(
                      error,
                      style: const TextStyle(
                        color: Color(0xffffc4a4),
                        height: 1.5,
                      ),
                    ),
                  ),
                const SizedBox(height: 22),
                _card(
                  child: ExpansionTile(
                    tilePadding: EdgeInsets.zero,
                    title: const Text('Ajustes e diagnóstico'),
                    children: [
                      TextField(
                        controller: glossary,
                        enabled: !running && !busy,
                        decoration: const InputDecoration(
                          labelText: 'Nomes preservados (rápido e online)',
                          hintText: 'Nest, Luffy, nomes de personagens…',
                        ),
                        maxLength: 1000,
                      ),
                      TextButton(
                        onPressed: running || busy
                            ? null
                            : () => action('configureGlossary', {
                                'terms': glossary.text,
                              }),
                        child: const Text('Salvar nomes'),
                      ),
                      const Text(
                        'Área ignorada pelo OCR',
                        style: TextStyle(color: Color(0xffaeb9c8)),
                      ),
                      _margin(
                        'Topo',
                        top,
                        (value) => setState(() => top = value),
                      ),
                      _margin(
                        'Base',
                        bottom,
                        (value) => setState(() => bottom = value),
                      ),
                      TextButton(
                        onPressed: () => action('configureCrop', {
                          'top': top,
                          'bottom': bottom,
                        }),
                        child: const Text('Salvar margens'),
                      ),
                      Text(
                        'Eventos de scroll: ${state['scrollEvents'] ?? 0}',
                        style: const TextStyle(color: Color(0xffaeb9c8)),
                      ),
                      if (metrics != null && metrics.isNotEmpty)
                        Text(
                          'Último frame: ${metrics['elapsedMs']} ms + 550 ms de pausa\n'
                          '${metrics['balloons']} balões · ${metrics['cacheHits']} do cache',
                          textAlign: TextAlign.center,
                        ),
                      TextButton.icon(
                        onPressed: diagnostics,
                        icon: const Icon(Icons.info_outline),
                        label: const Text('Ver diagnóstico do aparelho'),
                      ),
                      TextButton(
                        onPressed: () => showDialog<void>(
                          context: context,
                          builder: (context) => AlertDialog(
                            title: const Text('Sobre a tradução rápida'),
                            content: const SingleChildScrollView(
                              child: Text(
                                'Traduções automáticas fornecidas pelo Google Translate, por meio do ML Kit no aparelho. '
                                'O Google não oferece garantias sobre a precisão, confiabilidade, comercialização, adequação a uma finalidade específica ou ausência de violação de direitos das traduções. '
                                'O modo rápido usa português (pt), sem uma opção regional específica. O modo LLM usa um modelo especializado com instrução para português brasileiro. '
                                'O download do modelo usa internet; os textos são traduzidos localmente.',
                              ),
                            ),
                            actions: [
                              TextButton(
                                onPressed: running
                                    ? null
                                    : () => action('openTranslationInfo'),
                                child: const Text('Documentação'),
                              ),
                              TextButton(
                                onPressed: () => Navigator.pop(context),
                                child: const Text('Fechar'),
                              ),
                            ],
                          ),
                        ),
                        child: const Text('Sobre Google Translate offline'),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 20),
                Text(
                  onlineMode
                      ? 'Textos e glossário são enviados ao Gemini no modo online. As imagens do OCR ficam no aparelho e são apagadas após a leitura. O cache fica no armazenamento privado do app.'
                      : 'As imagens usadas pelo OCR são temporárias. Frases e traduções ficam no armazenamento privado do app.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Color(0xff8090a5),
                    fontSize: 12,
                    height: 1.5,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _margin(String label, double value, ValueChanged<double> changed) =>
      Row(
        children: [
          SizedBox(width: 40, child: Text(label)),
          Expanded(
            child: Slider(
              value: value,
              min: 0,
              max: .25,
              divisions: 25,
              onChanged: changed,
            ),
          ),
          SizedBox(width: 40, child: Text('${(value * 100).round()}%')),
        ],
      );
  Widget _tag(String text) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
    decoration: BoxDecoration(
      border: Border.all(color: const Color(0xff344256)),
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      text,
      style: const TextStyle(
        fontSize: 10,
        letterSpacing: .7,
        color: Color(0xffbfcddd),
      ),
    ),
  );
  Widget _section(String number, String title) => Row(
    children: [
      Text(
        number,
        style: const TextStyle(
          color: Color(0xffbff6dc),
          fontWeight: FontWeight.w800,
        ),
      ),
      const SizedBox(width: 12),
      Expanded(
        child: Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 18),
        ),
      ),
    ],
  );
  Widget _card({required Widget child}) => Container(
    padding: const EdgeInsets.all(20),
    decoration: BoxDecoration(
      color: const Color(0xff192333),
      border: Border.all(color: const Color(0xff2b384b)),
      borderRadius: BorderRadius.circular(22),
    ),
    child: child,
  );
  Widget _permission(
    String title,
    String subtitle,
    bool granted,
    VoidCallback tap,
  ) => ListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(title),
    subtitle: Text(subtitle, style: const TextStyle(fontSize: 12)),
    trailing: granted
        ? const Icon(Icons.check_circle, color: Color(0xffbff6dc))
        : TextButton(onPressed: busy ? null : tap, child: const Text('Ativar')),
  );
  Future<void> _accessibilityDisclosure() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Detecção de rolagem'),
        content: const Text(
          'O serviço de acessibilidade identifica rolagem, mudanças de conteúdo e qual aplicativo está aberto. '
          'Ele usa esses eventos para esconder traduções durante o movimento. A sessão começa pausada. Quando você toca em Traduzir no controle flutuante, a tela visível é capturada para reconhecer e traduzir o texto. '
          'Não há restrição de site. Textos da árvore de acessibilidade não são lidos nem armazenados. Você pode pausar pelo controle ou desativar o serviço nas configurações do Android.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Abrir configurações'),
          ),
        ],
      ),
    );
    if (accepted == true && mounted) await action('openAccessibilitySettings');
  }

  Future<void> _startReading() async {
    final online = state['translationMode'] == 'online';
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Tradução com controle flutuante'),
        content: Text(
          online
              ? 'A sessão começa pausada. Abra o conteúdo e toque em Traduzir no controle flutuante para liberar a leitura da tela, em qualquer site ou aplicativo compatível com captura. '
                    'Os textos reconhecidos, o contexto visível e os termos do glossário são enviados ao Gemini. As imagens ficam no aparelho. '
                    'Use Pausar antes de abrir informações que não queira enviar. Há cotas de API e a velocidade depende da conexão e do serviço.'
              : 'A sessão começa pausada. Abra o conteúdo e toque em Traduzir no controle flutuante para liberar a leitura da tela. '
                    'Use Pausar para interromper e esconder as traduções. O processamento é local. O botão × encerra a sessão.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Iniciar'),
          ),
        ],
      ),
    );
    if (accepted == true && mounted) await action('startSession');
  }

  Future<void> _selectMode(String mode) async {
    if (mode == 'online' && state['onlineConsent'] != true) {
      final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Ativar tradução online'),
          content: const Text(
            'O modo online envia os textos dos balões e os termos do glossário ao Gemini. As imagens ficam no aparelho. '
            'Requer internet e uma chave de API. A faixa gratuita tem cotas e o Google pode usar os textos para melhorar seus produtos. '
            'Para usar apenas a faixa gratuita, crie um projeto sem faturamento. Você pode voltar ao modo offline a qualquer momento.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Ativar online'),
            ),
          ],
        ),
      );
      if (accepted != true || !mounted) return;
    }
    await action('setTranslationMode', {
      'mode': mode,
      'consent': mode == 'online',
    });
  }
}
