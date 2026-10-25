package net.oyasai.meltype.engine

/**
 * 「#/」で始まる発言の、先頭の「#」を外すための部品。
 *
 * コマンドを人に伝えたいとき、`/tp` と打つとコマンドとして実行されてしまうので、
 * `#/tp` のように「#」を付けて発言する。この「#」を、送信するときに外して、
 * 相手には `/tp` と見えるようにする（コマンドとしては実行されず、普通の発言として送られる）。
 */
object HashSlashMessage {

    private const val PREFIX = "#/"

    /** 「#/」で始まる発言なら、先頭の「#」を外した文字列を返す。そうでなければ、そのまま返す */
    fun strip(message: String): String =
        if (message.startsWith(PREFIX)) message.substring(1) else message
}
