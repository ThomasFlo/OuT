# HomeStock

Application de localisation d'objets à domicile pour un couple sur deux Pixel,
avec **recherche sémantique vocale** et **synchronisation temps réel** via un
backend auto-hébergé sur NAS.

- **Backend** : FastAPI + PostgreSQL/pgvector + service d'embeddings multilingue, le tout en Docker.
- **Android** : Kotlin + Jetpack Compose (Material 3, dark mode), MVVM + Hilt + Room, Retrofit + WebSocket, CameraX, SpeechRecognizer.

```
.
├── homestock-backend/    # API, base de données, embeddings (Docker)
└── homestock-android/    # Application Android (projet Gradle)
```

---

## 1. Backend — déploiement Docker sur NAS

Compatible Synology, QNAP, Unraid (tout hôte avec Docker + docker-compose).

### Prérequis
- Docker et Docker Compose.
- ~2 Go de RAM libre (le modèle d'embeddings est chargé en mémoire).
- Accès LAN entre le NAS et les téléphones.

### Étapes

```bash
cd homestock-backend
cp .env.example .env          # ajustez mot de passe + port (API_PORT, défaut 8080)
docker compose up -d --build
```

Le premier build télécharge le modèle
`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` (~120 Mo) et
l'intègre à l'image `homestock-embeddings`, qui fonctionne ensuite **100 % offline**.

### Vérification

```bash
curl http://<IP_DU_NAS>:8080/health        # -> {"status":"ok"}
curl http://<IP_DU_NAS>:8080/zones         # -> 21 zones pré-créées
```

Documentation interactive : `http://<IP_DU_NAS>:8080/docs`

### Conteneurs
| Conteneur | Rôle |
|-----------|------|
| `homestock-db` | PostgreSQL 15 + pgvector + pg_trgm |
| `homestock-embeddings` | Vectorisation (sentence-transformers), port interne 9000 |
| `homestock-api` | API REST + WebSocket, exposé sur `API_PORT` |

Au démarrage, l'API crée les tables, les index (IVFFlat cosinus + GIN trigram) et
insère les **21 zones** et les **18 catégories** par défaut.

### Réseau & sécurité
- Seul `homestock-api` est exposé sur le LAN (port `API_PORT`).
- Les téléphones se connectent en HTTP/WS clair sur le LAN privé (pas de TLS requis).
- **L'API n'a pas d'authentification et autorise toutes les origines (CORS `*`)** :
  c'est volontaire pour un usage privé à deux sur réseau local. **Ne l'exposez pas
  directement sur Internet.** Pour un accès distant, placez un reverse-proxy TLS
  avec authentification (Synology, Traefik…) devant l'API.

### Sauvegarde
- Les données vivent dans les volumes Docker `homestock-db-data` et `homestock-photos`.
- Export/Import JSON également disponibles via l'app (Paramètres) ou les endpoints
  `GET /export` et `POST /import`. L'import refuse une base déjà peuplée ; utilisez
  `POST /import?replace=true` pour écraser les données existantes.

---

## 2. Application Android — compilation

### Prérequis
- Android Studio (Koala ou plus récent) **ou** un JDK 17 + le SDK Android.
- Les Pixel 8/9 (ou tout appareil **Android 11+ / API 30+**).

### Génération du wrapper Gradle (une seule fois)
Le dépôt ne versionne pas `gradle-wrapper.jar`. Ouvrez simplement le dossier
`homestock-android/` dans Android Studio (il configure le wrapper
automatiquement), ou en ligne de commande :

```bash
cd homestock-android
gradle wrapper --gradle-version 8.9   # nécessite un Gradle local
```

### Compilation de l'APK

```bash
cd homestock-android
./gradlew assembleDebug
# APK : app/build/outputs/apk/debug/app-debug.apk
```

Installez l'APK sur chaque Pixel (`adb install` ou copie directe).

### Premier lancement (wizard)
1. Saisir l'**IP du NAS** + le **port** (ex. `192.168.1.50` / `8080`), bouton « Tester la connexion ».
2. Saisir les **prénoms** des deux profils et choisir le profil courant.
3. Terminer : l'app synchronise les zones/objets et bascule sur l'écran principal.

---

## 3. Utilisation

- **Rechercher** (onglet 🔍) : tape ou dicte. La recherche est sémantique
  (« outil pour visser » trouve « tournevis ») via pgvector, fusionnée avec la
  recherche plein-texte française (Reciprocal Rank Fusion), déclenchée au fil de
  la frappe (debounce). La **localisation complète** est affichée en grand, en
  premier ; un badge rouge/orange signale les objets périmés / bientôt périmés.
- **Voix** : le micro dans la barre de recherche.
  - Question (« où sont mes chaussures d'hiver ? ») → recherche.
  - Commande (« range les chaussures dans le meuble sous l'escalier ») → pré-remplit
    le formulaire d'ajout (objet, zone par matching flou, emplacement, quantité).
- **Ajouter** (FAB +) : formulaire en 4 étapes (objet → localisation → détails → confirmation),
  photos via CameraX (compressées à 800 px avant upload), sous-formulaire vin si catégorie « cave à vins ».
  La saisie du nom **suggère les objets similaires existants** (réutilise leur catégorie/emplacement).
- **Modifier / supprimer** : depuis l'écran de détail d'un objet (le même stepper sert à l'édition).
- **Zones** (🏠) / **Catégories** (📂) : navigation, filtres et recherche texte dans une zone.
- **Cave à vins** : statistiques, filtres par type, bouton « Je débouche une bouteille ».
- **Paramètres** (⚙️) : NAS, profils, zones (ajout/renommage/activation), langue vocale,
  notifications d'expiration, mode debug (affiche les scores), export/import JSON.
- **Notifications** : un job WorkManager quotidien alerte des produits expirant sous 3 jours,
  même app fermée.
- **Retour haptique** sur les actions importantes (enregistrement, suppression, débouchage, voix).

### Synchronisation & offline
- Room est la **source de vérité locale** : l'app fonctionne hors-ligne.
- Un WebSocket persistant (reconnexion exponentielle) propage chaque changement entre
  les téléphones ; un point vert/rouge indique l'état de connexion.
- Les écritures faites hors-ligne sont mises en file et poussées à la reconnexion
  (résolution « last write wins » côté serveur).

---

## 4. Architecture technique

### Recherche hybride (backend)
1. La requête est vectorisée par le service embeddings.
2. pgvector calcule la similarité cosinus (seuil > 0.4).
3. PostgreSQL full-text français (`to_tsvector`/`plainto_tsquery`) en parallèle.
4. Les deux classements sont fusionnés par **RRF** puis renvoyés triés.

À chaque sauvegarde d'objet, le vecteur est (re)généré à partir de
`nom + sous-catégorie + notes + catégorie`.

### Stack Android
`data/` (Room local, Retrofit/WebSocket remote, repository offline-first) ·
`domain/` (modèles, catégories) · `ui/` (Compose, navigation, écrans, thème) ·
`voice/` (SpeechRecognizer + parser NLP regex) · `di/` (Hilt).
