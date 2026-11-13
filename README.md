# Timite Tracker (Fabric 26.2)

Client-Mod für Hypixel Skyblock. Befehl: `/timite`

Zeigt: deine Timite-Collection, die von dinofeet_ (bzw. dem aktuellen #1), den Abstand
und wie viel du pro Tag sammeln musst, um bis 01.01.2027 #1 zu sein
(einmal wenn der #1 stehen bleibt, einmal mit seinem gemessenen Tempo).

## Bauen mit GitHub Actions
1. Auf https://fabricmc.net/develop/ die Versionen für 26.2 nachschauen und in `gradle.properties`
   eintragen (`loader_version`, `loom_version`, `fabric_api_version`).
2. Repo pushen -> Workflow *Build* läuft automatisch, die Jar steht unter Actions -> Artifacts.
3. Release: `git tag v1.0.0 && git push origin v1.0.0` -> Jar wird automatisch an ein GitHub-Release gehängt.

Lokal: Java 25 + Gradle, dann `gradle build` (Jar in `build/libs/`).

## Hinweise
- Daten kommen von api.eliteskyblock.com (Leaderboard-ID wird zur Laufzeit über /leaderboards gefunden).
- Das Tempo des #1 wird aus lokalen Messpunkten (config/timite-tracker-history.json) berechnet.
  Je öfter du `/timite` über mehrere Tage nutzt, desto genauer.
