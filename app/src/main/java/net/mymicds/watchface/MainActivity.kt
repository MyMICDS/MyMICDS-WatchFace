package net.mymicds.watchface

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        private const val API_LOGIN_ROUTE = "https://api.mymicds.net/auth/login"

        private const val JWT_KEY = "net.mymicds.watchface.jwt"
    }

    private lateinit var mDataClient: DataClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mDataClient = Wearable.getDataClient(this)
    }

    fun login(button: View) {
        val username = usernameText.text.toString()
        val password = passwordText.text.toString()

        val queue = Volley.newRequestQueue(this)

        val request = JsonObjectRequest(
            Request.Method.POST,
            API_LOGIN_ROUTE,
            JSONObject()
                .put("user", username)
                .put("password", password)
                .put("comment", "Watch Face")
                .put("remember", true),
            Response.Listener { response ->
                button.isEnabled = true
                (button as Button).text = getString(R.string.login_button)

                Log.d(TAG, "Login response: $response")

                if (!response.getBoolean("success")) {
                    doRequestError()
                    return@Listener
                }

                val putDataRequest = PutDataMapRequest.create("/jwt").run {
                    dataMap.putString(JWT_KEY, response.getString("jwt"))
                    asPutDataRequest()
                }

                mDataClient.putDataItem(putDataRequest).addOnCompleteListener {
                    Toast.makeText(this, "Logged in!", Toast.LENGTH_SHORT).show()
                }
            },
            Response.ErrorListener { error ->
                doRequestError()
                Log.e(TAG, "Login error: ${error.message}")
            }
        )

        queue.add(request)

        button.isEnabled = false
        (button as Button).text = getString(R.string.login_button_process)
    }

    private fun doRequestError() {
        Toast.makeText(this, "Error submitting credentials!", Toast.LENGTH_LONG).show()
        passwordText.text = null
    }

}
