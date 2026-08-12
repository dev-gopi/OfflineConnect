# Room supplies its own consumer rules; retain entity constructors/fields used by generated code.
-keepclassmembers class com.devgopi.offlineconnect.database.** {
    <fields>;
    <init>(...);
}
