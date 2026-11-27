# Notes d'architecture

Ce document rassemble les décisions techniques et les pièges rencontrés en
construisant l'overlay. Il complète le [README](README.md).

## Plateforme : Android uniquement

Le cœur du concept — la compagne affichée *par-dessus* les autres apps — repose
sur une capacité que seul Android expose (`SYSTEM_ALERT_WINDOW`). iOS interdit à
une app de dessiner au-dessus d'une autre, ce qui rend un portage impossible sans
dénaturer l'idée. Pas de portage iOS prévu.

## Stack

Natif Android en Kotlin. L'overlay est une vue attachée au `WindowManager`,
portée par un foreground service pour survivre au changement d'app. Un moteur de
jeu comme Godot n'expose pas proprement le rendu dans un overlay système ; pour
cette fonction précise, le natif est le bon outil.

La compagne est pilotée par une state machine écrite à la main
(`CompanionController`) : personnalité et transitions hand-authored, pas de LLM.

## Gestion des touches (point critique)

Une vue attachée au `WindowManager` qui couvre l'écran intercepte les touches
destinées à l'app en dessous. La compagne doit donc être touchable *uniquement*
sur la zone du sprite et transparente ailleurs : la fenêtre est dimensionnée au
sprite (pas plein écran), avec les flags appropriés (`FLAG_NOT_FOCUSABLE`, et
`FLAG_NOT_TOUCHABLE` là où l'overlay ne doit pas voler le tap). C'est ce qui fait
ou défait l'ensemble.

## Permission `SYSTEM_ALERT_WINDOW`

Elle ne s'accorde pas par dialogue runtime : il faut envoyer l'utilisateur dans
`Settings.ACTION_MANAGE_OVERLAY_PERMISSION`, gérer le retour et revérifier l'état
au réveil.

## Rendu du personnage

Le rendu passe par Live2D Cubism SDK for Native 5-r.5 : Framework C++ + Core
statique Android, compilés par CMake/NDK et exposés par JNI. Le rendu se fait dans
une `TextureView` avec contexte EGL et boucle de rendu dédiée (`Live2DRenderer`),
derrière l'interface `CharacterRenderer`. Le reste du service d'overlay reste
côté Kotlin/Java.

**TextureView plutôt que GLSurfaceView.** Une `SurfaceView` en overlay peut
bloquer le thread principal pendant les changements de surface. La `TextureView`
permet de garder le contexte EGL sur un thread dédié et de conserver le comportement
de déplacement de l'overlay. La fenêtre overlay porte `FLAG_HARDWARE_ACCELERATED`.

**Animations procédurales universelles.** Clignement (`CubismEyeBlink`) et
respiration/balancement (`CubismBreath`) sont ajoutés au modèle par le code : ils
marchent sur n'importe quel modèle exposant les paramètres standards, même sans
motion fournie.

**Motions par modèle.** Les `.motion3` sont authored par le créateur du modèle et
n'ont pas de convention commune ; certains modèles n'en fournissent aucune. L'idle
joue le premier groupe de motions s'il existe. Seul le procédural (respiration,
clignement, physique) est garanti partout.

## Interaction

- Appui long → menu radial (`RadialMenuView`) : caresser, nourrir, parler.
- Caresser → mode câlin (`enterPetMode`) : agrandit le modèle, fige la
  déambulation ; on caresse en bougeant le doigt, chaque déplacement déclenchant
  une réaction. On en sort en rouvrant la roue.
- Nourrir → un snack tombe en bas ; la compagne marche jusqu'à lui via l'« appât »
  du `CompanionController`, puis mange.
- Parler → bulle de dialogue (répliques piochées au hasard) ; du bavardage
  spontané se déclenche aussi à intervalle aléatoire.

La roue propose aussi éteindre, agrandir et réduire.

## Modèles

`Live2DModels` découvre les modèles dans les assets (`assets/live2d/<id>/`) et
dans le stockage interne pour les modèles importés (`filesDir/live2d/<id>/`, scan
récursif). Un dossier est un modèle valide s'il contient un `*.model3.json`.
Le bridge JNI charge les fichiers depuis `AssetManager` (chemins relatifs) ou
depuis le stockage privé (chemins absolus des modèles importés).

L'import passe par le Storage Access Framework (`OpenDocumentTree`) puis une copie
récursive en tâche de fond.

## Attribution des animations

`AnimMapping` associe les animations d'un modèle (motions, expressions) aux
actions (repos, caresse, nourrir, parler), avec un poids par animation. Au
runtime, chaque action fait un tirage aléatoire pondéré parmi les animations
assignées, sinon retombe sur le comportement par défaut. La configuration est
persistée par modèle (`anim_map_<id>`) et rechargée à la volée quand elle change.

## Détail d'implémentation à connaître

Le cycle de vie du Cubism Native est lié au thread EGL : `OnStart` prépare les
ressources, `OnSurfaceCreate` initialise le Framework après que le contexte GL
est courant, puis `OnStop` libère les ressources avant la destruction du contexte.
Lorsqu'une `TextureView` est recréée, le thread EGL reconstruit ce cycle. Les
appels JNI de rendu et de chargement doivent rester sur ce même thread et ce contexte.

## Personnalité

Un personnage, deux variantes visuelles (renarde « feu » et renarde des neiges,
choix dans les réglages qui re-stylise l'app et l'overlay). Une poignée d'états
gérés par la state machine : repos, marche, réaction-caresse, manger, parler.

## Vie en fond (prévu)

Faim et affection qui dérivent avec le temps, réactions quand on l'ignore, état
sauvegardé. La dérive ne doit pas supposer un service toujours vivant (les OEM
tuent les process) : on stocke un timestamp du dernier état connu et on recalcule
la dérive au réveil plutôt que de la faire tourner en continu — plus fiable et
plus sobre en batterie.

## Contraintes connues

- **Notification permanente.** Android l'exige pour tout foreground service. Elle
  peut servir de mini tableau de bord (humeur, faim) et de bouton « la rappeler à
  l'écran » quand un OEM a tué l'overlay.
- **OEM qui tuent les services.** Xiaomi, Oppo et, à moindre degré, Samsung tuent
  agressivement ce type de service en arrière-plan ; la fiabilité varie selon
  l'appareil.
