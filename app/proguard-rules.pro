# SPDX-License-Identifier: GPL-3.0-or-later
# kotlinx.serialization trae sus propias reglas; mantenemos los modelos por claridad.
-keep,includedescriptorclasses class dev.liquidmessages.data.**$$serializer { *; }
-keepclassmembers class dev.liquidmessages.data.** {
    *** Companion;
}

# Lo que se guarda en JSON fuera de data/: papelera (sms/Trash) y programados (schedule/).
-keep,includedescriptorclasses class dev.liquidmessages.sms.**$$serializer { *; }
-keepclassmembers class dev.liquidmessages.sms.** {
    *** Companion;
}
-keep,includedescriptorclasses class dev.liquidmessages.schedule.**$$serializer { *; }
-keepclassmembers class dev.liquidmessages.schedule.** {
    *** Companion;
}
