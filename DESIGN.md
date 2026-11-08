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
