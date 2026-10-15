# MedApp

Application Android de suivi des médicaments au quotidien, construite autour d'une API que l'on peut héberger soi-même et qui sert la base de données officielle des médicaments français (ANSM / RUIM). Projet universitaire réalisé à cinq (Scrum, 2025-2026), ensuite repris comme projet personnel open source.

> **Avertissement :** MedApp est un projet pédagogique, pas un dispositif médical. Il ne donne aucun avis médical. Suivez toujours les consignes de votre médecin et de votre pharmacien.

## Essayer l'application sur un téléphone

[Télécharger la dernière version (APK)](https://github.com/DenKhar7/medapp/releases/latest/download/medapp.apk), ou scanner ce code :

<img src="docs/qr-apk.png" alt="Code QR de téléchargement de MedApp" width="160">

- Android 12 ou plus récent est nécessaire.
- Android peut afficher un avertissement de Play Protect (« application d'un développeur inconnu ») : touchez « Plus de détails », puis « Installer quand même ». L'APK est signé avec la clé du projet et n'est pas distribué par Google Play.
- La recherche de médicaments utilise l'API en ligne `https://vps.denkhar.com`. Les comptes et les traitements restent sur votre appareil.
- Toutes les versions : [Releases GitHub](https://github.com/DenKhar7/medapp/releases).

<!-- Vidéo de démonstration : à ajouter ici -->

## Fonctionnalités

- **Liste du jour et calendrier :** ce qu'il faut prendre aujourd'hui, et les jours complétés ou manqués.
- **Traitements :** posologie, période, récurrence hebdomadaire, plusieurs rappels par jour.
- **Rappels :** notifications à l'heure exacte avec les actions « Pris » et « Reporter de 15 min », reprogrammées après un redémarrage.
- **Recherche de médicaments :** recherche par nom dans la base officielle, avec mise en cache des résultats sur le téléphone.
- **Ordonnances :** saisie manuelle ou numérisation d'un document avec reconnaissance de texte sur l'appareil (ML Kit).
- **Profils familiaux :** un compte, plusieurs personnes, des données séparées.
- **Français et anglais.**

Les comptes et les traitements sont stockés **localement sur l'appareil** (Room et session chiffrée). Le serveur ne fournit que les données de référence des médicaments.

## Architecture

```
+--------------------+   HTTP/JSON    +----------------------+        +--------------+
| android/           | -------------> | server/ (Ktor)       | ---->  | MariaDB      |
| Kotlin, MVVM       |   /api/v1/     | hexagonal :          |        | active/temp  |
| Room, WorkManager  |   medicament   | domain, infra, api   |        | archive      |
| ML Kit, Ktor       |                | imports nocturnes    |        | views (RO)   |
+--------------------+                +-----------+----------+        +--------------+
                                                  | scraping + téléchargement
                                                  v
                                     Serveur de terminologie ANS (RUIM, SMS)
```

| Dossier | Contenu |
|---|---|
| [`android/`](android) | Application Android : Kotlin, MVVM, Room, AlarmManager/WorkManager, CameraX/ML Kit. |
| [`server/`](server) | API REST Ktor 3 (Exposed + HikariCP), imports CSV, mises à jour planifiées du référentiel, Swagger UI. |
| [`infra/`](infra) | Schémas MariaDB, exécuteur de migrations SQL, exemples de pipelines Jenkins, jeu de données de test. |

## Démarrage rapide

### 1. Lancer le serveur (Docker)

```bash
cd server
cp .env.example .env      # à remplir : choisir les mots de passe (lettres, chiffres et _ . @ + - uniquement)
docker compose build
docker compose up -d mariadb
docker compose run --rm server import-referentiels   # premier import des données ANS
docker compose up -d server
```

Documentation de l'API : <http://localhost:4000/swagger>. Vérification de santé : <http://localhost:4000/health>.

### 2. Compiler l'application Android

Prérequis : JDK 17, Android Studio (SDK 36).

Créer `android/local.properties` :

```properties
sdk.dir=C:\\Users\\<vous>\\AppData\\Local\\Android\\Sdk
# Racine du serveur. Depuis l'émulateur Android, 10.0.2.2 désigne le localhost de votre machine
# (le préfixe /api/v1/medicament est ajouté par l'application)
BASE_URL=http://10.0.2.2:4000
```

```bash
cd android
./gradlew assembleDebug        # ou ouvrir le dossier dans Android Studio
./gradlew test                 # tests unitaires
```

Utilisez une image d'émulateur **avec Google Play** : le scanner de documents dépend des services Google Play.

## Sécurité

- **Comptes de base de données (moindre privilège).** MariaDB est initialisée à partir de `infra/sql/prod`, qui crée deux comptes :
  `API_DB_USER` (lecture seule, `SELECT` sur `med_db_views` uniquement) utilisé par l'API, et `MARIADB_USER` (tâches d'import,
  droits limités aux bases `active`, `temp` et `archive`, sans `ALL PRIVILEGES`). Le mot de passe root reste dans le
  conteneur MariaDB et n'est jamais transmis au conteneur de l'API, qui s'exécute aussi avec un utilisateur non root.
- **Mises à jour atomiques des données.** La mise à jour nocturne du référentiel échange les tables avec une seule
  instruction `RENAME TABLE` : l'API ne voit donc jamais une base à moitié mise à jour. Un import vide, ou qui a perdu plus
  de la moitié de ses lignes, est rejeté avant de remplacer les données de production. Les échecs sont enregistrés dans
  `referentiel_import_state`.
- **Protection de l'API.** Limitation de débit par IP (120 requêtes par minute par défaut, `api.rate-limit.per-minute`),
  paramètre `limit` plafonné à 50, caractères `%` et `_` saisis par l'utilisateur traités littéralement dans les
  recherches, CORS sans cookies, et un point `/health` qui vérifie réellement la base de données.
- **Android.** Pas de sauvegarde cloud, stockage privé pour les scans d'ordonnances (supprimés avec l'ordonnance),
  notifications génériques sur l'écran verrouillé, mots de passe hachés en PBKDF2-HMAC-SHA256 (600 000 itérations) avec mise
  à niveau transparente des anciens hachages, et aucun corps de requête dans les journaux de la version release.

**Mise à jour d'une base existante :** la création des comptes ne s'exécute que sur un volume vide. Pour un déploiement
existant, créez les deux comptes avec les droits définis dans `infra/sql/prod/02_create_db_users.sh`, supprimez la clé
étrangère sur `med_substance.code_sms` dans `med_db_active` et `med_db_temp` (et `med_db_archive`), puis relancez l'import.

## Limites connues

- L'instance en ligne `https://vps.denkhar.com` est fournie sans garantie de disponibilité. Pour un usage fiable, hébergez le serveur vous-même.
- Le HTTP en clair n'est autorisé que pour les hôtes de développement local. Utilisez HTTPS pour tout déploiement réel.
- Derrière un reverse proxy, le serveur utilise `XForwardedHeaders` pour que la limitation de débit s'applique à l'IP réelle du client.
- La base Room n'est pas chiffrée au repos et l'application n'a pas de verrouillage biométrique (les comptes sont locaux à l'appareil).
- Les rappels sont reprogrammés par une tâche WorkManager quotidienne : les modes d'économie de batterie agressifs peuvent les retarder.
- Les détails moléculaires des substances (`/substances/detail`) sont de nouveau disponibles, mais l'application mobile ne les affiche pas.

## Licence

[MIT](LICENSE)
