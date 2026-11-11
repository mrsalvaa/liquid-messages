# Liquid Messages

[English](README.md) | **Español**

Se instala en el celular como **Mensajes**.

Una app de SMS y MMS libre y sin rastreadores, con la misma interfaz de cristal líquido que el
resto de la suite ([Liquid Clock](https://github.com/mrsalvaa/liquid-clock), Calendar, Contacts,
Phone, Gallery, Recorder y Calc). En Ajustes también está el estilo Sólido.

**La privacidad en tres líneas:** la app no tiene permiso de internet. Los mensajes quedan en el
almacén de mensajes del propio celular y solo salen como los SMS o MMS que mandás. Sin publicidad,
telemetría, informes de errores ni Google Play Services.

Licencia GPL-3.0-or-later.

## Funciones

- **SMS y MMS**: fotos, conversaciones grupales, asunto, informes de entrega (opcionales),
  mensajes largos con contador de partes. Un MMS que no se pudo bajar queda con el botón
  "Descargar" y nunca se pierde.
- **Doble chip sin sorpresas**: nunca cambia de chip sin avisar. Cada conversación usa su chip;
  también se puede usar un chip principal, preguntar siempre o dejar que decida el sistema.
- **Deshacer**: los mensajes de texto esperan 5 segundos antes de salir (opcional); al borrar
  aparece "Deshacer". Lo borrado va a una papelera de 30 días.
- **Mensajes programados**, borradores, respuestas rápidas y responder desde la notificación.
- **Posible spam**: los números desconocidos con links de estafa van a su propia carpeta, sin
  avisar. Se decide en el celular, sin listas de internet; nunca se filtran contactos ni gente a la
  que le escribiste.
- **Códigos de verificación**: "Copiar código" en la notificación y en el mensaje; borrado opcional
  de los códigos ya leídos.
- **Conversaciones privadas**, fuera de la lista, las búsquedas y Destacados; se abren con la
  huella o el PIN. Bloqueo de la app opcional.
- **Mensajes destacados**, detalles de cada mensaje, búsqueda dentro de una conversación e "ir a
  una fecha".
- Las **reacciones** que llegan desde iPhone o Google Messages («Le gustó “…”») se ven como un
  emoji sobre el mensaje original.
- Fijar, archivar, silenciar, sonido por conversación, bloquear números, acciones al deslizar,
  selección múltiple.
- **Copia de seguridad**: exportar e importar en el formato XML de "SMS Backup & Restore", con el
  selector de archivos del sistema.
- Color de las burbujas, tamaño de letra, lista compacta, vista previa de las notificaciones (todo,
  solo quién, nada) e ícono de la app.

## Permisos

| Permiso | Para qué |
|---|---|
| `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | Ser la app de SMS. Android solo deja escribir mensajes a la app de SMS predeterminada. |
| `READ_CONTACTS` | Nombres y fotos en la lista. Nunca se modifican los contactos. |
| `READ_PHONE_STATE` | Saber qué chips hay, para enviar por el correcto. |
| `POST_NOTIFICATIONS` | Avisar de los mensajes nuevos. |
| `USE_BIOMETRIC` | Bloqueo de la app y conversaciones privadas (la huella o el PIN del propio celular). |
| `RECEIVE_BOOT_COMPLETED` | Volver a programar los mensajes programados después de reiniciar. |

Sin internet, ubicación ni número propio. `INTERNET`, `ACCESS_NETWORK_STATE`, la ubicación y
`READ_PHONE_NUMBERS` se quitan a propósito del manifiesto final. La app nunca lee el IMEI, el IMSI,
el ICCID ni tu número.

## Dependencias

| Biblioteca | Licencia | Uso |
|---|---|---|
| Jetpack Compose, AndroidX (core, activity, lifecycle) | Apache-2.0 | Interfaz |
| kotlinx.serialization | Apache-2.0 | Ajustes, borradores y papelera en JSON |
| [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | Efecto de cristal |
| [libphonenumber](https://github.com/google/libphonenumber) | Apache-2.0 | Reconocer números de los contactos |
| `mmslib` de AOSP Messaging (copiado, ver `app/src/main/java/dev/liquidmessages/mms/pdu/NOTICE`) | Apache-2.0 | Leer y armar MMS |

## Descarga

Las novedades de cada versión están en el [registro de cambios](CHANGELOG.es.md).

Bajá el APK de [Releases](../../releases) e instalalo (Android 12 o posterior). Después abrilo y
hacelo la app de SMS predeterminada.

### Si el celular no te deja instalarla

Mensajes pide permisos de SMS, y Android bloquea esas apps cuando no vienen de una tienda. El APK
está bien (comprobalo abajo); para instalarlo:

- **Play Protect**: Play Store → tu foto → Play Protect → ⚙️ → apagá "Analizar apps con Play
  Protect" mientras instalás y después volvé a prenderlo.
- **Bloqueador automático de Samsung**: Ajustes → Seguridad y privacidad → Bloqueador automático →
  apagalo mientras instalás.
- **O instalala desde una computadora** con la depuración USB activada:
  `adb install liquid-messages-v1.0.2.apk`.

Comprobá que el archivo no se alteró:

```bash
sha256sum -c liquid-messages-v1.0.2.apk.sha256
```

SHA-256 de `liquid-messages-v1.0.2.apk`:

```
8f2b99995529a59cc4fac53ff0e171e0b3cea9309afa0abe9b1b3104c982a61b
```

Y que está firmado con la clave del proyecto (huella SHA-256 del certificado):

```
2B:F0:DF:7E:60:20:6B:CF:8E:34:C0:02:72:99:AB:88:60:B8:91:F3:30:D4:83:26:B4:55:27:2E:3A:9C:52:24
```

```bash
apksigner verify --print-certs liquid-messages-v1.0.2.apk
```

## Compilar

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :core:test assembleDebug
```

Para ver la fluidez real en el celular, usá `assembleProfile` (optimizada como la release, se
instala como la de prueba): las builds debug de Compose son varias veces más lentas.

Debug y profile se instalan como `dev.liquidmessages.debug`, "Mensajes (prueba)".
