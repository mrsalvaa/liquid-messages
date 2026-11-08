// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidmessages.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import dev.liquidmessages.contacts.SystemContacts
import dev.liquidmessages.core.model.ContactSummary
import dev.liquidmessages.core.phone.PhoneMatch
import dev.liquidmessages.core.phone.PhoneMatcher
import dev.liquidmessages.core.phone.PhoneNumbers
import dev.liquidmessages.core.sim.SendContext
import dev.liquidmessages.core.sim.SendDecision
import dev.liquidmessages.core.sim.SendTarget
import dev.liquidmessages.core.sim.SimCountry
import dev.liquidmessages.core.sim.SimPolicy
import dev.liquidmessages.core.sim.SmsDecider
import dev.liquidmessages.core.sms.Message
import dev.liquidmessages.core.text.SpanishOrder
import dev.liquidmessages.sim.SimSnapshot
import dev.liquidmessages.sim.SystemSims
import dev.liquidmessages.sms.Blocked
import dev.liquidmessages.sms.DefaultSms
import dev.liquidmessages.sms.SmsStore
import dev.liquidmessages.sms.SystemSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** La agenda lista para usar: en orden alfabético y con la coincidencia de números calculada. */
class Book(
    val rows: List<ContactSummary> = emptyList(),
    val matcher: PhoneMatcher<ContactSummary>? = null,
)

/** Los mensajes leídos del sistema (o los de prueba en memoria) y los participantes de cada hilo. */
class Inbox(
    val messages: List<Message> = emptyList(),
    val participants: Map<Long, List<String>> = emptyMap(),
    val loaded: Boolean = false,
    /** Datos inventados de la herramienta de prueba: nunca se escriben en el sistema. */
    val fake: Boolean = false,
)

/** Todo lo que lee la app, siempre desde los proveedores del sistema. No hay base de datos propia. */
@SuppressLint("StaticFieldLeak") // Solo el contexto de la aplicación: vive lo mismo que el proceso.
object Repo {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: Context

    lateinit var contacts: SystemContacts
        private set
    lateinit var sms: SystemSms
        private set
    lateinit var sims: SystemSims
        private set

    private val _numbers = MutableStateFlow(PhoneNumbers())
    /** Normalizador con el país y el código de área de los ajustes. */
    val numbers: StateFlow<PhoneNumbers> = _numbers.asStateFlow()

    private val _book = MutableStateFlow(Book())
    val book: StateFlow<Book> = _book.asStateFlow()

    private val _real = MutableStateFlow(Inbox())
    private val _fake = MutableStateFlow<Inbox?>(null)
    private val _inbox = MutableStateFlow(Inbox())
    /** Lo que muestra la app: los mensajes de prueba si están cargados; si no, los del sistema. */
    val inbox: StateFlow<Inbox> = _inbox.asStateFlow()

    private val _fakeContacts = MutableStateFlow<List<ContactSummary>?>(null)

    private val _isDefault = MutableStateFlow(false)
    /** Si es la app de SMS predeterminada: sin eso no puede enviar, recibir, marcar ni borrar. */
    val isDefault: StateFlow<Boolean> = _isDefault.asStateFlow()

    lateinit var store: SmsStore
        private set
    lateinit var blocked: Blocked
        private set
    lateinit var trash: dev.liquidmessages.sms.Trash
        private set

    private val _countryConflict = MutableStateFlow<List<String>>(emptyList())
    /** Los chips son de países distintos y todavía no se eligió: la interfaz pregunta. */
    val countryConflict: StateFlow<List<String>> = _countryConflict.asStateFlow()

    private val _permissionTick = MutableStateFlow(0)
    /** Cambia cada vez que se revisan los permisos: las pantallas vuelven a mirar cuáles hay. */
    val permissionTick: StateFlow<Int> = _permissionTick.asStateFlow()

    private val _pendingCompose = MutableStateFlow<Compose?>(null)
    /** Un mensaje para dejar escrito (de otra app o de una notificación): nunca se envía solo. */
    val pendingCompose: StateFlow<Compose?> = _pendingCompose.asStateFlow()

    /**
     * Algo para dejar listo (de otra app, de una notificación o de compartir): una conversación
     * por hilo o por direcciones, con texto y una foto opcionales. Nunca se envía solo.
     */
    data class Compose(
        val addresses: List<String> = emptyList(),
        val body: String = "",
        val threadId: Long? = null,
        val image: android.net.Uri? = null,
    )

    /** Hace algo con la base de mensajes fuera del hilo principal (marcar, borrar, enviar). */
    fun io(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) { block() }
    }

    private var contactsJob: Job? = null
    private var smsJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        contacts = SystemContacts(app)
        sms = SystemSms(app)
        sims = SystemSims(app)
        store = SmsStore(app)
        blocked = Blocked(app)
        trash = dev.liquidmessages.sms.Trash(app)
        // Lo que pasó los 30 días en la papelera se borra para siempre.
        scope.launch(Dispatchers.IO) { runCatching { trash.cleanUp() } }
        _isDefault.value = DefaultSms.isDefault(app)
        scope.launch {
            Stores.settings.state.map { it.defaultCountry to it.defaultAreaCode }.distinctUntilChanged().collect { (country, area) ->
                _numbers.value = PhoneNumbers(country.ifEmpty { "AR" }, area.ifEmpty { null })
            }
        }
        scope.launch { combine(_real, _fake) { real, fake -> fake ?: real }.collect { _inbox.value = it } }
    }

    fun granted(permission: String): Boolean = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Se llama al abrir la app, al volver a ella y después de cada permiso. Solo desde el hilo principal. */
    fun start() {
        _permissionTick.value++
        _isDefault.value = DefaultSms.isDefault(app)
        sims.start()
        chooseCountry(sims.state.value)
        if (contactsJob == null && granted(Manifest.permission.READ_CONTACTS)) {
            contactsJob = scope.launch {
                combine(contacts.changes(), numbers, _fakeContacts) { _, n, fake -> n to fake }.collect { (n, fake) -> loadBook(n, fake) }
            }
        }
        if (smsJob == null && granted(Manifest.permission.READ_SMS)) {
            smsJob = scope.launch { sms.changes().collect { loadSms() } }
        }
    }

    private fun loadBook(numbers: PhoneNumbers, fake: List<ContactSummary>?) {
        val rows = (fake ?: contacts.summaries()).sortedWith(compareBy(SpanishOrder()) { it.displayName })
        _book.value = Book(rows, PhoneMatcher(numbers, rows) { it.phones })
    }

    private fun loadSms() {
        val participants = sms.participants()
        _real.value = Inbox(sms.messages(participants), participants, loaded = true)
    }

    /** Herramienta de prueba (solo debug): reemplaza lo que se ve por datos en memoria, o vuelve a lo real. */
    fun setFake(inbox: Inbox?, contacts: List<ContactSummary>?) {
        _fake.value = inbox
        _fakeContacts.value = contacts
        if (contactsJob == null) loadBook(numbers.value, contacts)
    }

    fun requestCompose(compose: Compose?) {
        _pendingCompose.value = compose
    }

    /** El contacto de un número, si es uno solo. Con varios candidatos no se elige ninguno. */
    fun contactFor(number: String): ContactSummary? =
        when (val m = _book.value.matcher?.match(number)) {
            is PhoneMatch.One -> m.item
            else -> null
        }

    /** Decide por qué chip sale un mensaje, con lo que se sabe en este momento. */
    fun decide(target: SendTarget, conversationSubscription: Int?, canSend: Boolean, hasText: Boolean = true): SendDecision {
        val snapshot: SimSnapshot = sims.current()
        val s = Stores.settings.value
        val context = SendContext(
            chips = snapshot.chips,
            chipsKnown = snapshot.known,
            airplaneMode = snapshot.airplane,
            canSend = canSend,
            policy = SimPolicy.fromValue(s.simPolicy),
            primarySlot = s.primarySimSlot,
            systemDefault = sims.systemDefault(),
            conversationSubscription = conversationSubscription,
        )
        return SmsDecider.decide(hasRecipient = true, hasText = hasText, target = target, context = context)
    }

    /**
     * País por defecto, la primera vez: el de los chips si coinciden; si son distintos no se
     * adivina y la interfaz pregunta. Sin ver los chips, el de la SIM principal.
     */
    private fun chooseCountry(snapshot: SimSnapshot) {
        if (Stores.settings.value.defaultCountry.isNotEmpty()) return
        when (val c = SimCountry.of(snapshot.countries)) {
            is SimCountry.One -> Stores.settings.update { it.copy(defaultCountry = c.iso) }
            is SimCountry.Conflict -> _countryConflict.value = c.isos
            SimCountry.Unknown -> Stores.settings.update { it.copy(defaultCountry = simCountry(app)) }
        }
    }

    fun setCountry(iso: String) {
        _countryConflict.value = emptyList()
        Stores.settings.update { it.copy(defaultCountry = iso) }
    }

    /** País de la SIM en mayúsculas (sin permisos), o el del idioma si no hay SIM. */
    fun simCountry(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val sim = tm?.simCountryIso?.takeIf { it.length == 2 }
        return (sim ?: Locale.getDefault().country.takeIf { it.length == 2 } ?: "AR").uppercase(Locale.ROOT)
    }
}
