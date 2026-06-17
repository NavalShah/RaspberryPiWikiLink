# Wiki Link Game

A small Java CLI prototype for a Wikipedia link-path game.

The graph model is:

- Wikipedia article = node
- Article link = directed edge
- Visited article links are cached locally in `data/pages.tsv` and `data/links.tsv`

The current prototype is dependency-free Java 8 so it can run on older Raspberry Pi Java installs.

## Commands

Compile:

```powershell
javac -d build src/WikiLinkGame.java
```

Run a cache demo:

```powershell
java -cp build WikiLinkGame demo "Computer science"
```

Play:

```powershell
java -cp build WikiLinkGame play "Computer science" "Philosophy"
```

Refresh cached nodes:

```powershell
java -cp build WikiLinkGame refresh-cache
```

Show cache stats:

```powershell
java -cp build WikiLinkGame cache-stats
```

## Raspberry Pi Notes

Copy the project to the Pi, install a JDK, compile, then run:

```bash
sudo apt update
sudo apt install openjdk-17-jdk
javac -d build src/WikiLinkGame.java
java -cp build WikiLinkGame demo "Computer science"
```

For the monthly refresh, create a systemd timer that runs:

```bash
cd /home/pi/wiki-link-game && java -cp build WikiLinkGame refresh-cache
```

## API Notes

The app uses the MediaWiki Action API endpoint:

```text
https://en.wikipedia.org/w/api.php
```

It calls `prop=links` to cache outgoing article links and `prop=info` to compare `lastrevid` during refresh.
