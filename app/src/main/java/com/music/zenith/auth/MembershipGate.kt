package com.music.zenith.auth

import android.content.Context
import com.music.zenith.data.model.Song
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.Volatile

/** The one gate every in-app song click goes through. */
object MembershipGate {

    sealed interface State {
        data object Unknown : State
        data object Checking : State
        data class Verified(val username: String) : State
        data class Blocked(val reason: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * One-shot replay only: emitted after OAuth succeeds when the activity was
     * killed and there is no in-memory lambda left to resume. A StateFlow here
     * would re-emit the same song on every later state write.
     */
    private val _pendingSong = MutableSharedFlow<PendingMembershipSong>(extraBufferCapacity = 1)
    val pendingSong: SharedFlow<PendingMembershipSong> = _pendingSong.asSharedFlow()

    private val checkMutex = Mutex()

    /** Invalidates results from a check that lost a race with OAuth or sign-out. */
    private val generation = AtomicLong(0L)

    @Volatile
    private var storeRef: AuthStore? = null

    /** Kept only while the activity is alive; the song itself is also on disk. */
    @Volatile
    private var pendingAction: (() -> Unit)? = null

    fun initialize(context: Context) {
        val store = store(context)
        _state.value = if (store.membershipSession != null) {
            State.Checking
        } else {
            State.Unknown
        }
    }

    /** Cold-start check. A missing session lands the dialog in the blocked state. */
    suspend fun refresh(context: Context) {
        ensureAuthorized(context)
    }

    /**
     * Rechecks membership on every gated play, as required. When Discord or the
     * worker cannot be reached, a previously verified and unexpired session is
     * trusted; a session that was never verified online is not.
     */
    suspend fun ensureAuthorized(
        context: Context,
        pending: (() -> Unit)? = null,
        pendingSong: PendingMembershipSong? = null,
    ): Boolean {
        val ticket = generation.incrementAndGet()
        val store = store(context)
        if (pending != null) pendingAction = pending
        if (pendingSong != null) store.pendingMembershipSong = pendingSong

        val session = store.membershipSession
            ?: run {
                setBlocked("not_authorized", store)
                return false
            }

        return checkMutex.withLock {
            _state.value = State.Checking
            when (val result = DiscordAuthClient.verifySession(session.token)) {
                is DiscordVerifyResult.Authorized -> {
                    if (ticket != generation.get()) return@withLock false
                    val now = System.currentTimeMillis()
                    val next = session.copy(
                        username = result.username.ifBlank { session.username },
                        verifiedAt = now,
                        expiresAt = if (result.expiresAt > 0L) result.expiresAt else session.expiresAt,
                    )
                    store.membershipSession = next
                    clearPending(store)
                    _state.value = State.Verified(next.username)
                    true
                }

                is DiscordVerifyResult.Unauthorized -> {
                    if (ticket != generation.get()) return@withLock false
                    setBlocked(result.reason, store)
                    false
                }

                DiscordVerifyResult.Unavailable -> {
                    if (ticket != generation.get()) return@withLock false
                    val stillValid = session.verifiedAt > 0L &&
                        session.expiresAt > System.currentTimeMillis()
                    if (stillValid) {
                        clearPending(store)
                        _state.value = State.Verified(session.username)
                        true
                    } else {
                        setBlocked("verification_unavailable", store)
                        false
                    }
                }
            }
        }
    }

    /** Called after the OAuth browser is opened, so the dialog shows progress. */
    fun markChecking() {
        generation.incrementAndGet()
        _state.value = State.Checking
    }

    /** A UI-level failure before any session was stored. */
    fun block(reason: String) {
        generation.incrementAndGet()
        setBlocked(reason, null)
    }

    /** Dismisses the dialog without granting access; the next tap asks again. */
    fun cancel(context: Context? = null) {
        generation.incrementAndGet()
        val store = context?.let(::store)
        clearPending(store)
        _state.value = State.Unknown
    }

    fun signOut(context: Context) {
        generation.incrementAndGet()
        val store = store(context)
        store.clearDiscordMembership()
        clearPending(store)
        _state.value = State.Blocked("not_authorized")
    }

    suspend fun handleCallback(context: Context, uri: android.net.Uri) {
        val ticket = generation.incrementAndGet()
        val store = store(context)
        _state.value = State.Checking

        when (val exchanged = DiscordAuthClient.exchange(context, uri)) {
            is DiscordExchangeResult.Success -> {
                if (ticket != generation.get()) return
                // /auth/exchange has already checked guild membership. A failed
                // follow-up verify is therefore treated as a transport problem,
                // not as proof that the user left the server.
                val finalCheck = DiscordAuthClient.verifySession(exchanged.sessionToken)
                if (ticket != generation.get()) return
                val username = when (finalCheck) {
                    is DiscordVerifyResult.Authorized ->
                        finalCheck.username.ifBlank { exchanged.username }
                    else -> exchanged.username
                }
                val expiresAt = when (finalCheck) {
                    is DiscordVerifyResult.Authorized ->
                        if (finalCheck.expiresAt > 0L) finalCheck.expiresAt else exchanged.expiresAt
                    else -> exchanged.expiresAt
                }

                store.membershipSession = MembershipSession(
                    token = exchanged.sessionToken,
                    username = username,
                    verifiedAt = System.currentTimeMillis(),
                    expiresAt = expiresAt,
                )
                _state.value = State.Verified(username)

                val fallback = store.pendingMembershipSong
                store.pendingMembershipSong = null
                val action = takePendingAction()
                if (action != null) {
                    action()
                } else {
                    fallback?.let { _pendingSong.tryEmit(it) }
                }
            }

            DiscordExchangeResult.NotMember -> {
                if (ticket != generation.get()) return
                store.clearMembershipSession()
                setBlocked("not_a_member", store)
            }

            is DiscordExchangeResult.Failure -> {
                if (ticket != generation.get()) return
                // A replayed callback has no valid PKCE state. It must not revoke
                // an authorization that was established by an earlier flow.
                val reason = if (exchanged.detail == "verification_unavailable") {
                    "verification_unavailable"
                } else {
                    "auth_failed"
                }
                setBlocked(reason, null)
            }
        }
    }

    fun consumePendingSong(context: Context? = null) {
        if (context != null) store(context).pendingMembershipSong = null
    }

    private fun takePendingAction(): (() -> Unit)? {
        val action = pendingAction
        pendingAction = null
        return action
    }

    private fun clearPending(store: AuthStore?) {
        pendingAction = null
        store?.let { it.pendingMembershipSong = null }
    }

    private fun setBlocked(reason: String, store: AuthStore?) {
        // Keep the pending song on disk: it is what lets a killed activity still
        // play the tapped track once the browser comes back.
        store?.clearMembershipSession()
        _state.value = State.Blocked(reason)
    }

    private fun store(context: Context): AuthStore {
        storeRef?.let { return it }
        return synchronized(this) {
            storeRef ?: AuthStore(context.applicationContext).also { storeRef = it }
        }
    }
}

fun Song.toPendingMembershipSong() = PendingMembershipSong(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
)

fun PendingMembershipSong.toSong() = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
)
