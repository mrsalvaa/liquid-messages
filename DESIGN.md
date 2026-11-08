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

## Etapas 2 y 3: app de SMS predeterminada y MMS

Se hicieron juntas a pedido del dueño: siendo la predeterminada, los MMS los tiene que bajar y guardar la propia app (el servicio de MMS del sistema no lo hace por ella), así que sin MMS se perderían.

### Lo que exige el rol y cómo está protegido

| Componente | Protección |
|---|---|
| `receive/SmsDeliverReceiver` (`SMS_DELIVER`) | `BROADCAST_SMS`: solo el sistema |
| `receive/MmsWapPushReceiver` (`WAP_PUSH_DELIVER`) | `BROADCAST_WAP_PUSH`: solo el sistema |
| `receive/RespondViaMessageService` | `SEND_RESPOND_VIA_MESSAGE`: solo el sistema |
| `MainActivity` con `SENDTO`/`VIEW` de `sms:`, `smsto:`, `mms:`, `mmsto:` y `SEND` de texto o foto | Deja el mensaje escrito; nunca envía solo |
| `SmsStatusReceiver`, `MmsResultReceiver`, `NotificationActionReceiver` | No exportados: solo los `PendingIntent` de la app |
| `FileProvider` `${applicationId}.mms` | No exportado; permiso solo a `com.android.phone` y `com.android.mms.service`, sobre `cache/mms/` (se borra al terminar) y `cache/view/` (adjuntos abiertos con otra app, se borran a la hora) |

Permisos finales (`aapt2 dump permissions`): `READ_SMS`, `READ_CONTACTS`, `READ_PHONE_STATE`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `POST_NOTIFICATIONS`. Ninguno más.

### SMS

- **Recibir:** se juntan las partes de un mismo remitente, se guarda en `Inbox` sin leer con el chip por el que llegó, y se avisa. Los números bloqueados los descarta el sistema antes.
- **Enviar:** `SmsSender` guarda en `Outbox`, parte con `divideMessage` y envía por el chip ya decidido; cada parte avisa por `PendingIntent`. Una que falle deja el mensaje "No se envió", con aviso y "Reenviar" en su menú.
- **Leer:** abrir la conversación la marca leída (SMS y MMS) y quita su notificación.
- **Borrar** mensajes y conversaciones, con confirmación (la papelera es de la etapa 4).
- **Bloquear:** la lista del sistema (`BlockedNumberContract`), la misma de Phone; en Ajustes, "Números bloqueados" para desbloquear.

### Notificaciones

`MessagingStyle` con nombre y foto del contacto (búsqueda directa en la agenda: desde segundo plano la de la app puede no estar cargada). Acciones: Responder, Marcar como leído y "Copiar 482913" si hay un código. **En la pantalla bloqueada solo dice "Mensaje nuevo"**, sin quién ni qué. Silenciar una conversación (menú) no la notifica.

Responder desde la notificación, y "responder con un mensaje" al rechazar una llamada, siguen la regla de chips: si hay que elegir chip, **no envían** y avisan, con el texto esperando en la conversación.

En Android 12 `POST_NOTIFICATIONS` no existe: se da por permitido (lint lo marcó; antes la app no avisaba nada en Android 12).

### MMS

`mms/pdu/` es la copia de mmslib de AOSP Messaging (Apache-2.0, ver `mms/pdu/NOTICE`), con el paquete cambiado porque el sistema trae otra copia oculta con el nombre original. No se copió `PduPersister`: `mms/MmsStore.kt` guarda en las tablas `pdu`, `addr` y `part` solo lo que la app usa.

- **Recibir:** el aviso (`NotificationInd`) **se guarda primero** y después se pide bajarlo (`downloadMultimediaMessage` a un archivo del `FileProvider`). Si la descarga falla, el aviso queda con "Descargar" y una notificación: nunca se pierde. Un aviso repetido (mismo lugar de descarga) no se duplica. Al bajar, el aviso se reemplaza por el MMS completo y se le contesta al operador con `m-notifyresp.ind`.
- **Enviar:** `SendReq` con presentación SMIL (como la app de AOSP), las fotos reducidas a JPEG hasta el tamaño que acepta el operador (`MMS_CONFIG_MAX_MESSAGE_SIZE`, 300 KB si no lo dice). Va como MMS si hay foto o la conversación es de grupo.
- **Mostrar:** texto, asunto, fotos dentro de la burbuja (tocarla la abre en grande) y otros adjuntos como botón. En un grupo, cada mensaje recibido dice quién lo mandó.
- **Grupos y número propio:** en un MMS de grupo los destinatarios incluyen la línea propia, y la app no pide `READ_PHONE_NUMBERS`. Se excluyen los números que en MMS individuales recibidos aparecen como único destinatario; se calcula al recibir y **no se guarda**. Si todavía no hay ninguno, un grupo puede mostrar el número propio como un participante más.
- **Lo que el proveedor oculta:** a quien no es la app predeterminada (también al shell de adb) el proveedor de MMS no le muestra salientes ni avisos sin bajar (`accessRestricted`). Para limpiar el emulador hay que borrar desde la app.

### Qué se probó (emulador `liquid35`, números `+1 202 555 01XX`)

| Prueba | Resultado |
|---|---|
| "Hacer predeterminada" con el diálogo del sistema | El sistema la acepta como app de SMS; el rol da también las notificaciones |
| SMS entrante simulado | Se guarda sin leer, notificación con Responder, Marcar como leído y "Copiar 482913" |
| Responder desde la notificación | Sale el SMS (queda enviado), el recibido queda leído y la notificación se va |
| Enviar desde la conversación | Enviado |
| Bloquear y recibir de ese número | No llega; de otro número sí; desbloquear desde Ajustes |
| `smsto:` de otra app | Abre la conversación con el texto escrito, sin enviar |
| Compartir una foto | Pide a quién; en la conversación, miniatura y "Se va a enviar como MMS" |
| `DEV_MMS` (solo emulador): `SendReq` ida y vuelta | Mismo texto con ñ y emoji, misma foto |
| `DEV_MMS`: aviso sin servidor | Queda con "Descargar" y notificación de que no se pudo bajar |
| `DEV_MMS`: MMS individual y de grupo guardados | Foto y texto en la burbuja; grupo con 2 participantes, sin el número propio |
| Enviar MMS (grupo y con foto) | Sin servidor de MMS: queda "No se envió", con aviso |
| Borrar mensaje y conversación | Se borran del proveedor |

Al terminar se borró todo desde la app, se devolvió el rol de SMS a la app del emulador, se desinstaló la build y se apagó el emulador.

**Sin probar, y por qué:**

| Qué | Por qué | Cómo probarlo |
|---|---|---|
| Bajar y enviar MMS de verdad | El emulador no tiene servidor de MMS | Un MMS real, en el celu |
| Dos chips | El emulador tiene uno | En el celu, enviando por cada chip |
| Responder con un mensaje al rechazar una llamada | Necesita una llamada | Rechazar una llamada real con mensaje |
| Fluidez | El emulador usa GPU por software | Build `profile` en el celu |
| Grupos con el número propio sin MMS individuales previos | Depende de los mensajes que haya | Recibir un MMS de grupo |

## Etapa 4: bloqueo, papelera y respaldo

Copiar códigos ya estaba desde la etapa 2 (menú del mensaje y notificación).

- **Bloqueo de la app** (`security/AppLock.kt`): `BiometricPrompt` del sistema con huella, cara o PIN (`BIOMETRIC_WEAK or DEVICE_CREDENTIAL`), sin librerías nuevas; suma `USE_BIOMETRIC`, un permiso común. Pide al abrir y al volver después de 30 s. Activarlo o desactivarlo también pide desbloquear, y solo se puede si el celular tiene bloqueo de pantalla. Con el bloqueo, las notificaciones dicen solo "Mensaje nuevo", sin quién, qué ni Responder (que saltearía el bloqueo), y la vista previa de recientes no muestra la app (Android 13+).
- **Papelera** (`sms/Trash.kt`): borrar guarda primero una copia en `files/trash.json` y, de los MMS, sus adjuntos en `files/trash/ID/`; recién después borra del sistema. Si la copia falla, no se borra nada. Restaurar (solo siendo la predeterminada) devuelve los mensajes a su conversación, con el lugar de descarga de un MMS sin bajar. Lo vencido se borra al abrir la app.
- **Respaldo** (`sms/Backup.kt`): formato XML de "SMS Backup & Restore", con los adjuntos en base64. El archivo se elige con el selector del sistema (sin permiso de almacenamiento). Exportar escribe a medida que lee; los avisos de MMS sin bajar no se exportan. Importar (solo siendo la predeterminada) saltea lo que ya está: SMS por fecha, tipo, número y texto; MMS por fecha en segundos, caja, texto y tipos de adjuntos.

Permisos finales: los ocho de las etapas 2 y 3 más `USE_BIOMETRIC`.

### Qué se probó (emulador)

| Prueba | Resultado |
|---|---|
| Borrar una conversación con un MMS con foto y un aviso sin bajar | Va a la papelera ("2 mensajes · quedan 30 días") |
| Restaurar | Vuelven la foto con su texto y el aviso con "Descargar" |
| Exportar | 3 SMS y 2 MMS, la foto en base64 |
| Importar el mismo archivo encima | 0 nuevos, 5 ya estaban |
| Borrar, vaciar la papelera e importar | Vuelven los 2 SMS y el MMS con la foto y su fecha original |
| Bloqueo con PIN de prueba | Activar pide el PIN; un SMS con la app bloqueada no muestra su texto en ninguna notificación; al volver después de 30 s, el pedido de PIN y detrás solo "Mensajes está bloqueada" |

**Arreglado al probar:** dos MMS del mismo segundo se confundían al importar (se perdía uno); el encabezado del respaldo contaba los avisos sin bajar; la papelera perdía el lugar de descarga de un MMS sin bajar; recién borrado decía "quedan 29 días".

Al terminar se borró todo desde la app, se sacó el PIN de prueba, se devolvió el rol de SMS a la app del emulador, se borró el archivo exportado, se desinstaló la build y se apagó el emulador. **Sin probar:** la huella (el emulador se probó con PIN) y un respaldo real de otra app.

## Pulido: personalización, funciones y avisos

- **Íconos:** Liquid (cristal sobre grafito, burbuja con los puntos calados y el punto rojo de la suite), Material (violeta `#8A55D6`, estilo Fossify), Samsung (burbuja azul sobre blanco), iPhone (verde) y monocromo. Un `activity-alias` por ícono, como en el resto de la suite.
- **Conversaciones:** burbujas recibidas grises o con el color del contacto (el de su avatar), letra chica/normal/grande, hora siempre o al tocar, lista compacta, mensajes de solo emojis grandes y sin burbuja, y deslizar a cada lado (nada, archivar, borrar a la papelera, leída o fijar; en material3 1.4 la acción llega por `onDismiss`).
- **Selección múltiple** con la barra de Recorder, y "Marcar todo como leído".
- **Borradores** en `files/drafts.json` (no en el proveedor: ninguna otra app los ve).
- **Respuestas rápidas** editables en Ajustes.
- **Programados** en `files/scheduled.json`, con `AlarmManager` no exacto y `RECEIVE_BOOT_COMPLETED` para volver a ponerlos al reiniciar. Al salir siguen la regla de chips.
- **Avisos:** qué muestran (quién y el texto, solo quién, nada), sonido por conversación (canal propio con id sin el número) e informe de entrega opcional.
- Ajustes con desplegables de ventana emergente (`Dropdown` de Recorder/Contacts) para deslizar, chip de envío y avisos.

## Excepción local de ui/glass: el fondo se congela al tocar

Con el fondo "En movimiento", las manchas avanzan 15 veces por segundo y cada paso obliga a redibujar todo el cristal (unos 13 ms por cuadro en el S24 FE, más que los 8,3 ms de 120 Hz). Si ese paso cae en medio de un desplazamiento, se pierde un cuadro. En `Theme.kt`, `BackgroundHold` congela el reloj del fondo mientras hay un dedo en la pantalla y hasta 1 s después de soltarlo; al retomar sigue desde donde quedó, sin salto. `GlassRoot` solo mira los toques (pase `Initial`, sin consumirlos).

Medido en el S24 FE con la build `profile`, 500 conversaciones inventadas en memoria y fondo en movimiento, 12 deslizamientos: lista 1,7 % de cuadros lentos (antes 2,1–2,3 %), conversación larga 2,5–2,8 %, mediana 7–8 ms. En reposo el fondo en movimiento sigue dibujando 15 cuadros por segundo (como en el resto de la suite); con "Liso" no dibuja nada.

Además: los formateadores de fecha se arman una vez por idioma (antes en cada fila y cada cuadro), y en una conversación el nombre y el color de cada remitente se calculan una vez.
