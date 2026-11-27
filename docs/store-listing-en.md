# Play Console — English store listing (영어 스토어 설명)

Play Console → 스토어 등록정보 → **번역 관리 → 영어(미국) en-US 추가** 에 아래 내용을 붙여 넣는다.
한국어 원본은 `docs/store-listing.md`. 글자 수 제한: 앱 이름 30자, 간단한 설명 80자, 자세한 설명 4000자.

**App name** (30자 이내)
```
Jamsucraft: AFK & Chat Client
```

**Short description** (80자 이내)
```
Stay AFK on Minecraft Java servers and chat from your phone, even screen off.
```

**Full description**
```
Jamsucraft keeps you connected to Minecraft: Java Edition servers from your Android phone or tablet — even with the screen turned off — so you can stay AFK and keep chatting without leaving your PC on.

■ Features
· Stays connected with the screen off (stop anytime from the notification)
· Auto-reconnect when kicked or disconnected; waits until a server comes back online
· Read and send chat, command autocomplete, command history
· Tap links and clickable buttons such as [Accept] in chat
· Auto-run commands every time you join (e.g. /afk)
· View and click server menus (chest GUIs), with auto-click rules
· Health and hunger display; death alerts with when, who and what
· Shows server resource-pack titles and icons as images in chat and the player list
· Minimap with joystick movement and waypoints, inventory, player list
· Resumes AFK automatically after a reboot or app update
· Three designs (Classic, Pixel, Clean), light/dark, text size, Korean and English
· Saves 30 days of chat logs on your device and exports them as text files
· Supports servers from 1.8 to the latest versions with your Microsoft (premium) account
· Two-pane layout on tablets
· Demo server you can try without signing in (a practice server inside the app)
· Block and report other players, hide chat lines
· Per-item drop lock so you don't throw things away by mistake

■ Notes
· Rules about AFK and third-party clients differ between servers. Please check each server's rules before using.
· Your login, server list and chat logs are stored only on your device and are never sent to the developer.
· Chat is written by other players on the server you join. You can block or report a player from the Players tab or by long-pressing a chat line.
· Open source (GPL-3.0): https://github.com/lhj080902/jamsuham

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.
```

**Foreground service declaration (English, if asked)**
```
When the user taps "Start" to begin AFK on a Minecraft server, the app runs a foreground service to keep the server connection alive (keep-alive responses) while the screen is off. A notification is always shown while it runs, and the user can stop it at any time from the notification's "Disconnect" button or in the app; the service ends as soon as the connection is closed. The service is also resumed only if the user was AFK when the phone rebooted or the app was updated (can be turned off per server). Other service types such as dataSync have time limits and cannot keep a connection open for hours.
```
