/*
 * AndShell: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.andshell.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.andshell.R
import org.andshell.di.CoroutineDispatchers
import org.andshell.ui.MainActivity
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.shell.ShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

@AndroidEntryPoint
class SshdService : Service() {

    @Inject lateinit var dispatchers: CoroutineDispatchers
    @Inject lateinit var sharedPreferences: SharedPreferences

    private val serviceScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val binder = SshdBinder()

    private var sshServer: SshServer? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(DEFAULT_PORT)
    val port: StateFlow<Int> = _port

    inner class SshdBinder : Binder() {
        fun getService(): SshdService = this@SshdService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }

        if (_isRunning.value) {
            return START_STICKY
        }

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                val port = sharedPreferences.getString(PREF_SSHD_PORT, DEFAULT_PORT.toString())
                    ?.toIntOrNull() ?: DEFAULT_PORT
                val password = sharedPreferences.getString(PREF_SSHD_PASSWORD, DEFAULT_PASSWORD)
                    ?: DEFAULT_PASSWORD

                val hostKeyFile = File(filesDir, "sshd_host_keys.ser")

                val server = SshServer.setUpDefaultServer()
                server.port = port
                server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
                server.passwordAuthenticator = PasswordAuthenticator { username, pwd, session ->
                    pwd == password
                }

                server.subsystemFactories = listOf(SftpSubsystemFactory())

                server.commandFactory = CommandFactory { channel, command ->
                    AndroidCommand(command)
                }

                server.shellFactory = AndroidShellFactory()

                server.start()

                sshServer = server
                _port.value = port
                _isRunning.value = true

                showForegroundNotification(port)

                Timber.i("SSHD server started on port $port")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start SSHD server")
                _isRunning.value = false
                stopSelf()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            try {
                sshServer?.stop(true)
                Timber.i("SSHD server stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping SSHD server")
            } finally {
                sshServer = null
                _isRunning.value = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun showForegroundNotification(port: Int) {
        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, SshdService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_SSHD)
            .setContentTitle(getString(R.string.sshd_notification_title))
            .setContentText(getString(R.string.sshd_notification_text, port))
            .setSmallIcon(R.drawable.notification_icon)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.sshd_notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_SSHD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID_SSHD, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_SSHD,
                getString(R.string.sshd_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (_isRunning.value) {
            try {
                sshServer?.stop(true)
            } catch (_: Exception) {}
            sshServer = null
            _isRunning.value = false
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.andshell.action.SSHD_STOP"
        const val DEFAULT_PORT = 8022
        const val DEFAULT_PASSWORD = "andshell"
        const val PREF_SSHD_PORT = "sshd_port"
        const val PREF_SSHD_PASSWORD = "sshd_password"
        private const val CHANNEL_SSHD = "andshell_sshd"
        private const val NOTIFICATION_ID_SSHD = 3
    }
}

private class AndroidShellFactory : ShellFactory {
    override fun createShell(channel: ChannelSession?): Command {
        return AndroidShellCommand()
    }
}

private class AndroidShellCommand : Command {
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null
    private var process: Process? = null

    override fun setInputStream(`in`: InputStream) {
        inputStream = `in`
    }

    override fun setOutputStream(out: OutputStream) {
        outputStream = out
    }

    override fun setErrorStream(err: OutputStream) {
        errorStream = err
    }

    override fun setExitCallback(callback: ExitCallback) {
        exitCallback = callback
    }

    override fun start(channel: ChannelSession?, env: Environment) {
        try {
            val shell = "/system/bin/sh"
            process = Runtime.getRuntime().exec(shell)

            val proc = process!!
            val procIn = proc.outputStream
            val procOut = proc.inputStream
            val procErr = proc.errorStream

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (inputStream!!.read(buf).also { len = it } != -1) {
                        procIn.write(buf, 0, len)
                        procIn.flush()
                    }
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (procOut.read(buf).also { len = it } != -1) {
                        outputStream!!.write(buf, 0, len)
                        outputStream!!.flush()
                    }
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (procErr.read(buf).also { len = it } != -1) {
                        errorStream!!.write(buf, 0, len)
                        errorStream!!.flush()
                    }
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    proc.waitFor()
                } catch (_: InterruptedException) {}
                exitCallback?.onExit(proc.exitValue())
            }.start()
        } catch (e: IOException) {
            exitCallback?.onExit(1)
        }
    }

    override fun destroy(channel: ChannelSession?) {
        process?.destroy()
    }
}

private class AndroidCommand(private val commandLine: String) : Command {
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var errorStream: OutputStream? = null
    private var exitCallback: ExitCallback? = null

    override fun setInputStream(`in`: InputStream) {
        inputStream = `in`
    }

    override fun setOutputStream(out: OutputStream) {
        outputStream = out
    }

    override fun setErrorStream(err: OutputStream) {
        errorStream = err
    }

    override fun setExitCallback(callback: ExitCallback) {
        exitCallback = callback
    }

    override fun start(channel: ChannelSession?, env: Environment) {
        try {
            val parts = commandLine.split("\\s+".toRegex())
            val process = Runtime.getRuntime().exec(parts.toTypedArray())

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (inputStream!!.read(buf).also { len = it } != -1) {
                        process.outputStream.write(buf, 0, len)
                        process.outputStream.flush()
                    }
                    process.outputStream.close()
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (process.inputStream.read(buf).also { len = it } != -1) {
                        outputStream!!.write(buf, 0, len)
                        outputStream!!.flush()
                    }
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    val buf = ByteArray(4096)
                    var len: Int
                    while (process.errorStream.read(buf).also { len = it } != -1) {
                        errorStream!!.write(buf, 0, len)
                        errorStream!!.flush()
                    }
                } catch (_: IOException) {}
            }.start()

            Thread {
                try {
                    process.waitFor()
                } catch (_: InterruptedException) {}
                exitCallback?.onExit(process.exitValue())
            }.start()
        } catch (e: IOException) {
            try {
                errorStream?.write(("Error: ${e.message}\n").toByteArray())
                errorStream?.flush()
            } catch (_: IOException) {}
            exitCallback?.onExit(1)
        }
    }

    override fun destroy(channel: ChannelSession?) {}
}
