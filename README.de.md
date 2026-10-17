# Shotmetrix-QR-Code — Kurzfassung

<p align="center"><img src="assets/shotmetrix-qrcode.png" width="100%" alt="Shotmetrix-QR-Code: Eine Schieß-App zeigt einen QR-Code, der Schütze scannt ihn, und die Einheit landet in Shotmetrix"></p>

Mit dem Shotmetrix-QR-Code geben Apps die Schüsse einer Trainingseinheit als QR-Code aus. Der Schütze scannt ihn,
landet auf Shotmetrix, sieht die Einheit und speichert sie in seinem Konto. Jeder Code gilt **einmal**.
Die App braucht dafür keinen API-Schlüssel, keinen eigenen Server und keine Anmeldung; der Code entsteht
auch ohne Netz. Die vollständige Beschreibung steht englisch in [SPEC.md](SPEC.md).

**Ausprobieren:**

<p align="center"><img src="assets/test-qrcode.png" width="300" alt="Test-QR-Code: 10 Schuss Luftgewehr, zusammen 99,5"></p>

Diesen Code mit der Handykamera scannen. Er ist ein echter Shotmetrix-QR-Code, gebaut mit dem
Python-Beispiel-Encoder und dem Produktionsschlüssel: 10 Schuss Luftgewehr mit Schusszeiten, zusammen 99,5.
Shotmetrix zeigt die Einheit an, dafür braucht es kein Konto. Wie jeder Code lässt er sich nur einmal
speichern; danach meldet Shotmetrix, dass er schon eingelöst ist.

**Shotmetrix** ist eine Trainingsplattform für Sportschützen (Luftgewehr, Luftpistole, Kleinkaliber,
Pistole 25 m / 50 m) der Shotmetrix Kemmer GmbH. Schützen erfassen dort ihre Einheiten — per Foto der
Papierscheibe, Ausdruck einer Auswerteanlage, QR-Code einer Anlage, Trainer-Datei oder von Hand — und
bekommen Schussbild, Statistik und Schießbuch; in Ranglisten, Wettkämpfen und Duellen vergleichen sie sich.

**Gedacht für** Entwickler von Apps und Geräten, die wissen, wo Schüsse sitzen: Trainings- und
Schießbuch-Apps, Software zu elektronischen Scheiben und Auswerteanlagen, Ziel- und Abzugstrainer,
Vereins- und Standsoftware. Schützen selbst brauchen aus diesem Projekt nichts — sie scannen nur.

**Inhalt:**
- bis 120 Schüsse, je Schuss Lage (x/y ab Mitte, +y oben), Wert (Zehntel oder ganze Ringe) und Schusszeit (Hundertstelsekunden, wahlweise)
- Disziplin (die 12 Disziplinen von Shotmetrix mit Code und Scheibenmaßen, siehe [disciplines.json](disciplines.json)), Beginn der Einheit, Summe
- eine zufällige Kennung

**Form:**
- `https://shotmetrix.com/api/qrcode/<CODE>`, der Code in Großbuchstaben (Base32)
- 1557 Zeichen Base32, verschlüsselt mit dem öffentlichen Schlüssel von Shotmetrix
- immer 973 Byte, deshalb immer ein QR-Code der Version 24 (113 × 113 Module, Fehlerkorrektur L, Code im alphanumerischen Abschnitt) — nie Version 23, die der verbreitete Web-Leser jsQR nicht lesen kann

**Was Shotmetrix nicht annimmt:**
- unlesbare oder veränderte Codes
- schon eingelöste Codes und doppelte Schüsse im selben Konto
- Schüsse, bei denen Wert und Lage nicht zusammenpassen
- Schusszeiten, die nicht in der Reihenfolge der Schüsse stehen
- unsinnige Werte

**Geltung:** Ein importiertes Ergebnis zählt fürs eigene Training (Statistik, Schussbild, Schießbuch). Für Ranglisten, Wettkämpfe und Duelle zählt es nur, wenn Shotmetrix die App angemeldet und freigeschaltet hat; dann trägt der Code ihre App-Marke.

**Fehler in der Eingabe:** Alle Beispiel-Encoder prüfen die Regeln aus SPEC.md, Abschnitt 7, und lehnen fehlerhafte Eingaben mit einer englischen Meldung ab (etwa `shot 3: value out of range`), statt einen Code zu bauen, den Shotmetrix abweisen würde.

**Beispiel-Encoder und Testvektoren** (Ordner [test-vectors/](test-vectors/), Aufrufe in [SPEC.md, Abschnitt 8](SPEC.md#8-testing)):

Alle fünf erzeugen die Testvektoren bitgleich und lehnen jede fehlerhafte Eingabe mit der erwarteten Meldung ab — geprüft mit:

| Sprache | Geprüft mit |
|---|---|
| JavaScript | Node.js 20.20 und 26.4, Chrome 155 |
| Python | Python 3.14 mit dem Paket `cryptography` 48 |
| Swift | Swift 6.3 unter macOS 26 (CryptoKit) |
| PHP | PHP 8.3 und 8.5 (Erweiterungen `sodium` und `openssl`) |
| Kotlin | Kotlin 2.1 auf JDK 21 |

Android, andere Browser und ältere Versionen sind nicht geprüft.

**Lizenz:** Copyright 2026 Shotmetrix Kemmer GmbH, [Apache License 2.0](LICENSE). Ohne Gewähr und ohne
Haftung im Rahmen der Lizenz (Abschnitte 7 und 8). Den Namen „Shotmetrix“ gibt die Lizenz nicht frei,
außer um zu sagen, dass eine App Shotmetrix-QR-Codes erzeugt ([NOTICE](NOTICE)).
