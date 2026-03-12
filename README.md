# Spotify-Keeper

App Android (Kotlin) para controle pessoal do Spotify com Spotify App Remote.

## O que foi implementado

- Integracao real com Spotify App Remote SDK.
- Conexao com autorizacao via app oficial do Spotify.
- Comandos de `play`, `pause`, `resume` e leitura de status real (`PlayerState`).
- Tratamento para Spotify nao instalado e falhas de conexao/autenticacao.
- Auto-retomada quando o player pausa de forma inesperada (sem pausa manual).
- Botao `Ver tops` para carregar top musicas, top artistas, playlists e musicas recentes via Spotify Web API.
- Auto-update no app via GitHub Releases (botao `Atualizar app`).

## Requisitos

- Android Studio / SDK Android.
- App oficial do Spotify instalado no celular (`com.spotify.music`).
- Conta Spotify com permissao para reproducao via app (normalmente Premium para controle completo).

## Configuracao

1. Crie um app no Spotify Developer Dashboard.
2. Cadastre a redirect URI `spotifykeeper://callback`.
3. Edite `app/build.gradle` e troque:
   - `COLOQUE_SEU_CLIENT_ID_AQUI` pelo seu Client ID.
4. Sincronize o Gradle.

## Build e execucao

```bash
gradle :app:assembleDebug
```

Para instalar no aparelho/emulador (com `adb` conectado):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Gerar APK sem Android Studio (celular + GitHub)

Este repo ja tem workflow em `.github/workflows/android-debug.yml`.

1. Crie um repositorio novo no GitHub (pode ser privado).
2. Envie todos os arquivos deste projeto para o repositorio.
3. Abra a aba `Actions` no GitHub.
4. Execute o workflow `Build Android Debug APK` (botao `Run workflow`).
5. Quando terminar, abra a execucao e baixe o artifact `app-debug-apk`.
6. No celular, extraia o ZIP e instale o `app-debug.apk`.

Obs:
- Android pode pedir permissao para instalar apps de fonte externa.
- Se mudar algo no codigo, rode o workflow de novo para gerar outro APK.

## Como usar

1. Abra o app e toque em `Conectar`.
2. Informe uma playlist URI (`spotify:playlist:<id>`) ou link `https://open.spotify.com/playlist/<id>`.
3. Toque em `Dar play na playlist`.
4. Use `Pausar`, `Retomar` e `Checar status` para controle.
5. Toque em `Ver tops` para autorizar a Web API e ver seus tops.

## Observacoes

- Uso pessoal apenas.
- Se voce pausar manualmente, o app respeita a pausa.
- Se houver pausa inesperada durante a sessao ativa, o app tenta retomar automaticamente.
- A Spotify Web API nao expõe historico completo de toda a vida; o app mostra tops e recentes disponiveis na API.
