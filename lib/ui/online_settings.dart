import 'package:flutter/material.dart';

typedef OnlineAction =
    Future<void> Function(String method, [Map<String, Object?>? arguments]);

class OnlineSettings extends StatefulWidget {
  const OnlineSettings({
    super.key,
    required this.state,
    required this.busy,
    required this.running,
    required this.action,
  });
  final Map<Object?, Object?> state;
  final bool busy, running;
  final OnlineAction action;
  @override
  State<OnlineSettings> createState() => _OnlineSettingsState();
}

class _OnlineSettingsState extends State<OnlineSettings> {
  final secret = TextEditingController();
  final readerUrl = TextEditingController();
  @override
  void dispose() {
    secret.dispose();
    readerUrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final configured = widget.state['onlineKeyConfigured'] == true;
    final disabled = widget.busy || widget.running;
    final report = widget.state['benchmark'] as Map?;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Gemini · Português brasileiro',
          style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 8),
        const Text(
          'Envia os textos dos balões e os nomes do glossário ao Gemini. As imagens ficam no aparelho. O cache evita novas chamadas para frases já traduzidas.',
          style: TextStyle(color: Color(0xffaeb9c8), height: 1.5),
        ),
        const SizedBox(height: 14),
        DropdownButtonFormField<String>(
          isExpanded: true,
          initialValue:
              widget.state['onlineModel'] as String? ?? 'gemini-3.8-flash',
          decoration: const InputDecoration(labelText: 'Modelo online'),
          items: const [
            DropdownMenuItem(
              value: 'gemini-3.8-flash',
              child: Text(
                'Flash · qualidade',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            DropdownMenuItem(
              value: 'gemini-3.5-flash-lite',
              child: Text(
                'Flash-Lite · mais rápido',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
          onChanged: disabled
              ? null
              : (value) => widget.action('setOnlineModel', {'model': value!}),
        ),
        const SizedBox(height: 14),
        TextField(
          controller: secret,
          enabled: !disabled,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          enableIMEPersonalizedLearning: false,
          decoration: InputDecoration(
            labelText: configured
                ? 'Trocar chave (opcional)'
                : 'Chave do Gemini',
            helperText: configured
                ? 'Chave salva no aparelho com criptografia.'
                : 'Cole aqui a chave criada no Google AI Studio.',
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: readerUrl,
          enabled: !disabled,
          autocorrect: false,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: 'Link do episódio (opcional)',
            hintText: 'https://www.webtoons.com/…',
            helperText: 'Retoma esse episódio no leitor online do Chrome.',
          ),
        ),
        const SizedBox(height: 10),
        const Text(
          'A faixa gratuita depende da elegibilidade do projeto e tem cotas por minuto e por dia. Para usar apenas essa faixa, use um projeto elegível sem faturamento vinculado. No plano gratuito, o Google pode usar os textos para melhorar seus produtos.',
          style: TextStyle(color: Color(0xffaeb9c8), fontSize: 12, height: 1.4),
        ),
        const SizedBox(height: 12),
        Wrap(
          spacing: 8,
          children: [
            FilledButton.icon(
              onPressed: disabled
                  ? null
                  : () async {
                      try {
                        await widget.action('configureOnline', {
                          'apiKey': secret.text,
                          'model':
                              widget.state['onlineModel'] ?? 'gemini-3.8-flash',
                          'readerUrl': readerUrl.text,
                        });
                      } finally {
                        if (mounted) secret.clear();
                      }
                    },
              icon: const Icon(Icons.speed_rounded),
              label: Text(
                configured
                    ? 'Salvar e testar conexão'
                    : 'Salvar chave e testar',
              ),
            ),
            TextButton(
              onPressed: disabled
                  ? null
                  : () => widget.action('openGeminiSetup'),
              child: const Text('Criar chave gratuita'),
            ),
            TextButton(
              onPressed: disabled ? null : () => widget.action('openWebtoon'),
              child: const Text('Abrir leitor WEBTOON'),
            ),
            if (configured)
              TextButton(
                onPressed: disabled
                    ? null
                    : () => widget.action('clearOnlineKey'),
                child: const Text('Remover chave'),
              ),
          ],
        ),
        if (report != null && report.isNotEmpty) ...[
          const SizedBox(height: 10),
          SelectableText(
            '${report['translation']}\n${report['negationTranslation']}\n${report['idiomTranslation']}\n'
            '${report['elapsedMs']} ms · 3 frases de teste',
            style: const TextStyle(color: Color(0xffbff6dc), height: 1.5),
          ),
        ],
        const SizedBox(height: 10),
        const Text(
          'Use o botão flutuante para liberar ou pausar a tradução da tela visível. Funciona sem limitar o domínio da página; pause antes de abrir informações que não queira enviar ao Gemini.',
          style: TextStyle(color: Color(0xffaeb9c8), fontSize: 12, height: 1.4),
        ),
      ],
    );
  }
}
