# DAVIX — Catalogo italiano gratuito e fonti ufficiali

## Obiettivo

Offrire in DAVIX un catalogo italiano utile e aggiornabile senza costi obbligatori, distinguendo in modo esplicito:

- dirette gratuite pubblicate direttamente dagli editori;
- piattaforme FAST gratuite, finanziate dalla pubblicità;
- servizi con contenuti gratuiti e contenuti premium;
- servizi interamente in abbonamento, aperti esclusivamente tramite l'app o il sito ufficiale.

DAVIX non ospita, ritrasmette, decripta o aggira protezioni dei contenuti. Non conserva credenziali di terzi e non cerca endpoint privati o non autorizzati.

## Fonte dati iniziale

Il catalogo macchina è `catalogs/it/free-services.json`. Contiene solo collegamenti HTTPS ai portali ufficiali dei rispettivi operatori. I collegamenti sono punti di accesso, non URL di flussi video estratti.

Ogni voce dichiara il tipo di accesso:

- `free`
- `free-with-account`
- `free-with-ads`
- `free-on-supported-devices`
- `mixed-free-and-subscription`
- `subscription`

La UI deve mostrare questa informazione prima di aprire il servizio, senza suggerire che i contenuti premium siano gratuiti.

## Regole per il catalogo canali

1. Un canale logico ha un identificatore stabile indipendente dall'URL di riproduzione.
2. I duplicati vengono raggruppati; le sorgenti autorizzate restano selezionabili.
3. Non si importano credenziali, URL firmati, token o intestazioni private nel catalogo pubblico.
4. Un URL di riproduzione può essere incorporato solo quando l'editore lo pubblica per l'uso pubblico e ne consente la riproduzione in un player esterno.
5. Se l'editore richiede la propria app, DAVIX apre il portale/app ufficiale.
6. Le sorgenti non verificate non vengono presentate come funzionanti.
7. Il controllo di disponibilità è diagnostico: un HTTP 200 non prova che un flusso sia autorizzato o riproducibile.
8. Le guide EPG sono metadati separati dai flussi e devono rispettare licenze, attribuzione e condizioni del fornitore.

## Aggiornamento e affidabilità

- Aggiornare il catalogo dei servizi tramite revisione versionata.
- Eseguire validazione automatica dello schema e dei domini HTTPS.
- Conservare l'ultima versione valida in cache locale.
- Pubblicare una nuova versione solo dopo la validazione; mai sovrascrivere la cache valida con un catalogo malformato.
- Mostrare data dell'ultimo aggiornamento e stato della sorgente.
- Gestire errori per singola sorgente, senza bloccare l'intero Player.

## Limiti dichiarati

Non esiste una playlist gratuita e legale che includa tutti i canali premium, eventi sportivi a pagamento e cataloghi cinematografici protetti. La copertura gratuita cambia in base ai diritti, alla posizione geografica, al dispositivo e alle politiche degli editori.

Il catalogo iniziale è una base ufficiale e non una promessa di disponibilità permanente. Le integrazioni dei servizi premium sono semplici collegamenti ufficiali e non includono abbonamenti.

## Verifica

Eseguire:

```bash
node scripts/validate-free-catalog.mjs
```

Il controllo fallisce in presenza di ID duplicati, URL non HTTPS, domini non ammessi, campi obbligatori mancanti o link che sembrano contenere credenziali o endpoint di streaming diretti.
