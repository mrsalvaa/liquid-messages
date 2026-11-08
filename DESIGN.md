# Diseño: Liquid Messages dentro de la suite

SPDX-License-Identifier: GPL-3.0-or-later

Qué reutiliza esta app, qué escribió para sí y por qué. Se completa etapa por etapa.

## Decisiones

- **SMS primero, MMS después.** Hasta la etapa de MMS, un MMS se muestra como "MMS, no soportado todavía".
- **Sin internet.** Los MMS los baja y los manda el sistema (`SmsManager.downloadMultimediaMessage` y `sendMultimediaMessage`): la app solo le pasa los archivos. Tampoco hay vista previa de enlaces, porque sería visitarlos.
- **Sin RCS.** Google solo deja usarlo a Google Messages.
- **Sin ubicación ni `READ_PHONE_NUMBERS`.** Se quitan del manifest aunque una librería los pida.
- **Pruebas de enviar y recibir en el emulador**, con `adb emu sms send` y números reservados. En el celu del dueño solo se instala la build: ahí la app ve SMS reales y un envío de prueba le llegaría a una persona.

## Etapas

| Etapa | Contenido |
|---|---|
| 0 | Estructura y `:core` con tests. Sin pantallas ni permisos. |
| 1 | Interfaz con datos inventados en memoria, lectura del proveedor y envío por la app de SMS actual. Sin el rol. |
| 2 | Rol de SMS predeterminado: recibir, enviar con chip, notificaciones con respuesta, bloquear y borrar. |
| 3 | MMS. |
| 4 | Respaldo e importación, papelera de 30 días, bloqueo de la app, copiar códigos. |

## Origen de lo copiado

- **Liquid Contacts, commit `858940b`:** `ui/glass/` (idéntica a la de Liquid Recorder), `util/Strings.kt`, `util/Display.kt`, `data/Stores.kt`, `res/values*/strings_glass.xml`, `themes.xml`, `colors.xml` y `data_extraction_rules.xml`. Solo cambia el paquete.
- **Liquid Phone, commit `2c2bb1a`:** la base de Gradle y, en `:core`, `phone/PhoneNumbers.kt`, `text/ContactText.kt`, `avatar/Avatars.kt` y `model/Contact.kt` con sus tests, y `TestNumbersGuardTest`. La guardia perdió dos excepciones del interior que solo usaban tests de Phone que no se copiaron.

## :core

| Archivo | Qué hace |
|---|---|
| `sim/SmsDecider.kt` | Elige el chip al enviar. Adaptado de `CallDecider` de Phone, con la misma regla: **nunca hay un cambio silencioso de chip**. Por defecto se contesta con el chip de la conversación (el del último mensaje); sin conversación, el principal. Si el chip que toca no se puede usar o ya no está, se pregunta. No hay emergencias ni códigos: un SMS no los necesita. |
| `sms/SmsLength.kt` | Contador del campo de escribir: 160 caracteres por parte en GSM (153 si son varias), 70 en Unicode (67). Los extendidos (`€`, `[`…) cuentan doble, y un emoji o un extendido nunca se parte entre dos partes. `simplify` cambia á, í, ó, ú y comillas tipográficas para volver a 160. Lo que manda al enviar es `SmsManager.divideMessage`: el operador puede activar la tabla española de 7 bits. |
| `sms/Conversations.kt` | Agrupa por hilo, cuenta no leídos, ordena (fijadas primero) y separa archivadas. Fijar, archivar y silenciar se guardan por una **clave de participantes**, no por `thread_id`, que el sistema cambia si la conversación se borra y vuelve. Un remitente con letras ("BANCO") no admite respuesta. |
| `text/Codes.kt` | Código de verificación para copiar. Exige una palabra clave y descarta montos, fechas, horas y grupos que son parte de un teléfono. |
| `text/Links.kt` | Enlaces (solo con `http`, `https` o `www.`), emails y teléfonos para tocarlos. `host` da el dominio real para confirmarlo antes de abrir (`https://banco.com@estafa.example` es `estafa.example`). |

Los números de los tests siguen las reglas de `DESIGN.md` de Liquid Phone ("Números de los tests").

## Etapa 1: interfaz y lectura

- **Pantallas:** lista de conversaciones (sin pestañas: buscador, archivadas y ajustes arriba, botón flotante de mensaje nuevo), conversación, mensaje nuevo, archivadas, ajustes y una pantalla que explica los permisos antes de pedirlos. Estructura de `MainActivity` como la de Phone: una pila de pantallas encima de la lista.
- **Copiado de Contacts `858940b`:** `ui/components/ContactComponents.kt` (avatar, fila, búsqueda, botón rojo), `ui/screens/Common.kt` y `ui/screens/AppearanceCard.kt`. **De Phone `2c2bb1a`:** `sim/SystemSims.kt` sin las cuentas de llamada, la parte de lectura de `contacts/SystemContacts.kt` y `FakeNumbers.kt`.
- **Permisos:** `READ_SMS`, `READ_CONTACTS` y `READ_PHONE_STATE`, con su motivo en el manifest. Ningún otro.
- **Lectura:** `sms/SystemSms.kt` lee los SMS del proveedor y, de los MMS, solo cuándo llegaron y a qué conversación: se muestran como "MMS, no soportado todavía". Los participantes salen de `canonical-addresses`. Se vuelve a leer con cada cambio del proveedor.
- **Fijar, archivar y silenciar** se guardan en `settings.json` por la clave de participantes.
- **Enviar:** en esta etapa la app no es la de SMS predeterminada, así que el mensaje pasa escrito y sin enviar a la app de SMS actual (`ACTION_SENDTO`). La elección de chip ya pasa por `SmsDecider`.
- **Enlaces:** se marcan web, email y teléfono. Un enlace web pide confirmación mostrando el dominio real; un teléfono solo queda escrito en el teclado.
- **Códigos:** "Copiar código" en el menú del mensaje, marcado como sensible para que el sistema no lo muestre en la vista previa del portapapeles (Android 13+).
- **Teclado:** el margen general excluye el teclado (`safeDrawing.exclude(ime)`); cada pantalla que escribe suma `imePadding` una vez. Antes se sumaba dos veces y la lista desaparecía.

### Herramienta de prueba

Solo debug y profile, protegida con `DUMP` (solo el shell de adb):

```bash
adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_FAKE -p dev.liquidmessages.debug --ei count 500
adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_CLEAR -p dev.liquidmessages.debug
adb shell am broadcast -f 0x20 -a dev.liquidmessages.DEV_STYLE -p dev.liquidmessages.debug --ei background 2 --ei ui 0 --ei theme 2
```

Los datos de prueba viven **en memoria**: nunca se escriben en el proveedor de SMS ni en la agenda. Números de los rangos reservados (`FakeNumbers`), nombres con "(prueba)", dominios `example.*`, y la lista avisa en rojo que nada es real.

### Qué se probó (emulador `liquid35`)

| Prueba | Resultado |
|---|---|
| Dos SMS simulados con `adb emu sms send` desde `+1 202 555 01XX` | Aparecen solos, con no leídos |
| Copiar código | El portapapeles lo muestra como `••••••` |
| Tocar un enlace | Pide confirmación con `example.com` |
| Enviar | Se abre la app de SMS del emulador con el texto escrito |
| 500 conversaciones y 2417 mensajes en memoria | Lista, conversación larga con separadores por día, búsqueda sin tildes |
| Fijar y archivar | La fijada arriba con alfiler; la archivada en Archivadas |
| Tema oscuro, Liquid y Sólido | Se leen bien |

Al terminar se borraron los SMS simulados, se desinstaló la build y se apagó el emulador. **Sin probar:** dos chips (el emulador tiene uno) y la fluidez en el celu.
