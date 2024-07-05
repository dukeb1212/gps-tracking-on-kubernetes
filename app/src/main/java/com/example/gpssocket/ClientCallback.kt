package com.example.gpssocket

/**
 * Client callback
 */
interface ClientCallback {
    //Receive messages from the server
    fun receiveServerMsg(msg: String)
    //Other news
    fun otherMsg(msg: String)
}