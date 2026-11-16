# LiquidLyf's own R8 rules for release builds (on top of proguard-android-optimize.txt and the
# rules every library ships in its AAR).

# Crashlytics: keep file names and line numbers, so crash reports point at real lines (the
# uploaded mapping file turns the shortened class and method names back). Firebase's own advice.
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# No rules needed for:
# - Firestore documents: core/firebase/dto/ maps every field by hand with key constants, with no
#   reflection, so R8 may rename the model classes. If a screen ever uses the snapshots<T>()
#   helpers in FirestoreExt.kt (Firestore's class mapper, toObject), keep that class and its
#   no-argument constructor here.
# - kotlinx.serialization (navigation routes, saved drafts, the Worker's JSON, bd-geo.json): the
#   library ships its R8 rules.
