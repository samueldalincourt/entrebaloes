# Identidade visual — Entre Balões

- `app-icon.png`: arte original com o nome, mantida no projeto.
- `app-icon-512.png`: exportação de 512 × 512 para apresentação do projeto.
- Ícones Android: `android/app/src/main/res/mipmap-*/ic_launcher.png` e recurso adaptativo em `mipmap-anydpi-v26/`.
- Exportação: `python scripts/export_app_icon.py` (requer Pillow). O script apenas converte tamanhos da arte aprovada.

Criado e editado com a ferramenta integrada de geração de imagens, em 28/10/2026. As cores seguem a interface existente do app. O nome foi adicionado a pedido do usuário. O ícone adaptativo inclui margem para preservar o texto nas máscaras do launcher.

## Prompt da geração inicial

Use case: logo-brand. Asset type: production Android launcher icon, one square 1024 x 1024 image, for an existing app named Entre Balões that translates comic and webtoon dialogue. Create an original polished, simple emblem of two overlapping comic speech balloons, suggesting a conversation crossing between languages. Match the app's existing palette: deep midnight navy #101826 background, mint green #bff6dc front balloon and pale ivory rear balloon, tiny deep navy dialogue marks. Flat graphic shapes with a confident thick silhouette, subtle restrained depth, crisp edges, readable at 48 pixels. Center the entire emblem inside the middle 60 percent of the canvas so Android circular and squircle masks do not cut it. The midnight navy background fills every edge of the square. No words, no letters, no gradients, no borders around the canvas, no outer rounded-square tile, no Android or Flutter marks, no copyrighted brand symbols, no mockup, no watermark.

## Prompt da edição final

Edit this Android app icon. Keep the two overlapping mint green and ivory comic speech balloons, their three dialogue dots, the midnight navy background, and the overall visual identity. Add the app name exactly: "Entre Balões" (Portuguese, correct tilde on õ). Set this name clearly beneath the emblem in bold, friendly, highly legible rounded sans serif lettering, ivory or pale mint, centered. Scale the speech balloons slightly smaller and move them upward only as needed to make room for the name. Keep the combined emblem and text centered inside the middle 64 percent of the square canvas with generous background margins so Android circular masks preserve the whole design. Retain the full square navy background to every edge. No extra text, no border, no mockup, no watermark. Return one polished square icon.
