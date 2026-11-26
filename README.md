# Redmi Manager – backup e ripristino del telefono senza Google

Copia di sicurezza **completa** del telefono Android, con **ripristino**, salvata dove vuoi tu:
**NAS di casa**, **chiavetta USB**, **PC Windows o Mac**. Nessun servizio cloud, nessun account Google.

Marca del telefono a scelta (predefinita **Xiaomi / Redmi / POCO**): Samsung, Huawei/Honor, OPPO/realme/OnePlus, vivo, Motorola, Pixel, Nothing e altre.

## Cosa salva
| Voce | Backup | Ripristino |
|---|---|---|
| Rubrica | ✅ file vCard | ✅ tramite l'app Contatti |
| SMS | ✅ | ✅ (Redmi Manager diventa per pochi minuti l'app SMS predefinita) |
| Registro chiamate | ✅ | ✅ |
| Calendario | ✅ | ✅ in un calendario locale "Redmi Manager (ripristino)" |
| Foto, video, documenti, download, musica, registrazioni | ✅ solo i file nuovi | ✅ nelle cartelle originali |
| WhatsApp (foto, vocali, backup locale) | ✅ | ✅ poi WhatsApp › Ripristina |
| App installate | ✅ file di installazione | ✅ una conferma per ogni app |
| Dati interni delle app | ✅ tramite il **backup di fabbrica** della marca (Xiaomi, Samsung, Huawei, OPPO, vivo) | ✅ |
| Password Wi-Fi | ❌ Android non le rende leggibili | |

## Come funziona
- **Telefono (APK)**: backup automatico ogni giorno (o con l'intervallo scelto). Appena vede il **NAS**, il **PC** o la **chiavetta**, parte da solo, come fa Google di notte.
- **PC (Windows/Mac)**: riceve i backup dei telefoni nella rete di casa (collegamento con **codice QR**) e li copia in automatico sulla **chiavetta USB** appena la colleghi e sul **NAS** appena è acceso.
- Telefono, PC, chiavetta e NAS usano la stessa cartella `RedmiManager`: si può ripristinare da uno qualsiasi.

```
RedmiManager/<telefono>/Dati/<data_ora>/   rubrica, SMS, chiamate, calendario, elenco app (copie datate)
RedmiManager/<telefono>/App/               file di installazione delle app
RedmiManager/<telefono>/Archivio/          foto, video, documenti, WhatsApp... (si copiano solo i file nuovi)
```

## Download
Nella pagina **Releases**:
- **Android**: `RedmiManager-Android.apk`
- **Windows**: `RedmiManager-Setup.exe` (oppure `RedmiManager-portatile.exe`)
- **Mac**: `RedmiManager-Mac.dmg` (al primo avvio: tasto destro sull'app › Apri)

> Prima del primo utilizzo il programma mostra le **avvertenze** (`LEGGIMI_AVVERTENZE.txt`) e non prosegue senza accettazione.

## Compilazione
Le versioni vengono compilate da GitHub Actions a ogni modifica. Per pubblicare una release: aumentare `APP_VERSION` in `desktop/rm_core.py` (e `versionName` in `android/app/build.gradle.kts`) e scrivere `[release]` nel messaggio del commit. Restano solo le ultime 2 release.
