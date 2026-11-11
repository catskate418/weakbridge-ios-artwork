# Как выложить этот репозиторий на GitHub

Репозиторий уже собран: два коммита, ветка `main`, всё готово.

```
2ab34cb  Import WeakBridge 0.2.0          (чистый upstream, автор Weakmafaka)
fbf975e  Add album artwork, ...            (изменения форка, автор ZCode AI agent)
```

Такой историей GitHub показывает ровно то, что изменено: `fbf975e` — это и есть весь дифф
форка против оригинала (39 файлов, +2345 −143).

## Вариант 1 — GitHub CLI (проще всего)

```sh
brew install gh
gh auth login                      # GitHub.com → HTTPS → вход через браузер

cd WeakBridge-src
gh repo create weakbridge-ios-artwork --public --source=. --push \
  --description "WeakBridge fork: album artwork, real app icons, per-app notification channels for iPhone ↔ Wear OS"
```

## Вариант 2 — через сайт и git

1. Создайте пустой репозиторий на github.com **без** README, .gitignore и лицензии.
2. Затем:

```sh
cd WeakBridge-src
git remote add origin git@github.com:<ваш-логин>/<имя-репозитория>.git
git push -u origin main
```

Для HTTPS вместо SSH используйте `https://github.com/<логин>/<репозиторий>.git` и токен
вместо пароля (Settings → Developer settings → Personal access tokens → `repo`).

## После пуша

- В настройках репозитория задайте описание и темы: `wear-os`, `ios`, `ancs`, `ams`,
  `album-art`, `samsung-watch`.
- Приложите APK, если хотите: Releases → Draft a new release → прикрепите
  `../WeakBridge-custom.apk` или соберите свой.
- В `README.md` замените ссылки на upstream, если появится свой URL.
