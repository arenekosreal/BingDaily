package net.noobzhang.bingdaily

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.register
import net.mamoe.mirai.console.command.CommandManager.INSTANCE.unregister
import net.mamoe.mirai.console.command.CommandSender
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.FriendCommandSender
import net.mamoe.mirai.console.command.MemberCommandSender
import net.mamoe.mirai.console.data.AutoSavePluginData
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.permission.PermissionId
import net.mamoe.mirai.console.permission.PermissionService
import net.mamoe.mirai.console.permission.PermissionService.Companion.hasPermission
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.console.plugin.id
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.console.plugin.version
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.utils.ExternalResource.Companion.toExternalResource
import net.mamoe.mirai.utils.info
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 使用 kotlin 版请把
 * `src/main/resources/META-INF.services/net.mamoe.mirai.console.plugin.jvm.JvmPlugin`
 * 文件内容改成 `org.example.mirai.plugin.PluginMain` 也就是当前主类全类名
 *
 * 使用 kotlin 可以把 java 源集删除不会对项目有影响
 *
 * 在 `settings.gradle.kts` 里改构建的插件名称、依赖库和插件版本
 *
 * 在该示例下的 [JvmPluginDescription] 修改插件名称，id和版本，etc
 *
 * 可以使用 `src/test/kotlin/RunMirai.kt` 在 ide 里直接调试，
 * 不用复制到 mirai-console-loader 或其他启动器中调试
 */

object PluginMain : KotlinPlugin(
    JvmPluginDescription(
        id = "net.noobzhang.bingdaily",
        name = "每日必应",
        version = "0.1.0"
    ) {
        author("https://github.com/zhanghua000")
        info("获取指定日期的每日必应图片\n执行 /bingdaily help 获取帮助".trimIndent())
        // author 和 info 可以删除.
    }
) {
    override fun onEnable() {
        logger.info { "Bing Daily Plugin $version loaded." }
        PluginData.reload()
        //配置文件目录 "${dataFolder.absolutePath}/"
        BingCommand.register()
        PermissionService.INSTANCE.register(PermissionId(id,"command.clean"),"用于清理缓存的权限")
        val cachePath = File(dataFolder.absolutePath+File.separator+"cache")
        if (!(cachePath.exists() and cachePath.isDirectory)){
            if (cachePath.isFile){
                cachePath.delete()
            }
            cachePath.mkdir()
        }
        val eventChannel = GlobalEventChannel.parentScope(this)
        eventChannel.subscribeAlways<MessageEvent> {
            BingCommand.replyMeta = message.quote()
        }
    }
    override fun onDisable() {
        BingCommand.unregister()
    }
}
object BingCommand : CompositeCommand (
    PluginMain, "bingdaily",
    description = "获取每日必应图片"
    ){
    lateinit var replyMeta: QuoteReply
    @OptIn(ExperimentalSerializationApi::class)
    @SubCommand
    suspend fun CommandSender.get(day: Int = 0) {
        if (day>=0) {
            val notify = if (day == 0){
                trySendReplyMsg(messageChainOf(PlainText("正在获取今天的每日必应图片")),subject,replyMeta)
            } else{
                trySendReplyMsg(messageChainOf(PlainText("正在获取距今 $day 天的每日必应图片")),subject,replyMeta)
            }
            val fmt = SimpleDateFormat("yyyy-MM-dd")
            val cal = Calendar.getInstance()
            cal.time = Date()
            cal.add(Calendar.DATE, 0 - day)
            val title: PlainText
            val fname = fmt.format(cal.time)
            val imageFile =
                File(PluginMain.dataFolder.absolutePath + File.separator + "cache" + File.separator + fname + ".jpg")
            if ((fname in PluginData.indexes.keys) and imageFile.exists()) {
                title = PlainText("$fname 的每日必应图片：\n${PluginData.indexes[fname].toString()}\n")
            } else {
                val client = OkHttpClient()
                val urlFull = "https://cn.bing.com/HPImageArchive.aspx".toHttpUrl().newBuilder()
                    .addQueryParameter("format","js").addQueryParameter("idx",day.toString())
                    .addQueryParameter("n","1").build()
                PluginMain.logger.debug("API Query URL: $urlFull")
                val req = Request.Builder().url(urlFull).get().build()
                val resp = client.newCall(req).execute()
                val bodyContent:String = resp.body?.string().toString()
                PluginMain.logger.debug("Json Response: $bodyContent")
                val imageObj = Json.decodeFromString<RespObj>(bodyContent).images[0]
                val url = "https://cn.bing.com${imageObj.url.split("&")[0]}"
                val imgReq = Request.Builder().url(url).get().build()
                val imgResp = client.newCall(imgReq).execute()
                if (!imageFile.exists()) {
                    imageFile.createNewFile()
                }
                imgResp.body?.let { imageFile.writeBytes(it.bytes()) }
                PluginData.indexes[fname] = imageObj.copyright
                PluginMain.logger.debug("Indexes: ${PluginData.indexes}")
                title = PlainText("$fname 的每日必应图片：\n${imageObj.copyright}\n")
            }
            val imageRes = imageFile.toExternalResource()
            val image = subject?.uploadImage(imageRes)
            imageRes.close()
            val msgChain: MessageChain = if (image!=null){
                    messageChainOf(replyMeta,title, image)
                }else{
                    messageChainOf(replyMeta,PlainText("无法加载图片"))
            }
            trySendReplyMsg(msgChain,subject,replyMeta)
            notify?.recall()
        }
        else{
            trySendReplyMsg(messageChainOf(PlainText("参数必须为非负数，当前参数为 $day")),subject,replyMeta)
        }
    }
    @SubCommand
    suspend fun CommandSender.help(){
        trySendReplyMsg(
            messageChainOf(
                PlainText("获取每日必应的图片\n分为 get,help,status,clean 四个子命令\n" +
                            "get 用于获取图片，参数为距今的天数，默认为 0，即获取当天的图片\n" +
                            "help 用于显示此帮助\n" +
                            "status 用于测试插件运行状况\n" +
                            "clean 用于清理缓存"
                )), subject, replyMeta)
    }
    @SubCommand
    suspend fun CommandSender.status(){
        trySendReplyMsg(messageChainOf(PlainText("插件运行正常")),subject, replyMeta)
    }
    @SubCommand
    suspend fun FriendCommandSender.clean(){
        if (user.permitteeId.hasPermission(PermissionId(PluginMain.id,"command.clean"))){
            PluginData.indexes.clear()
            val cache = File("${PluginMain.dataFolder.absolutePath}${File.separator}cache").listFiles()
            if (cache!=null && cache.isNotEmpty()){
                for (file in cache){
                    if (file.isFile and file.name.endsWith(".jpg")){
                        file?.delete()
                    }
                }
            }
            trySendReplyMsg(messageChainOf(PlainText("已清理缓存，下次所有图片均将从网络获取")),subject, replyMeta)
        }
        else{
            trySendReplyMsg(messageChainOf(PlainText("你没有执行这个指令的权限")),subject, replyMeta)
        }
    }
    @SubCommand
    suspend fun MemberCommandSender.clean(){
        trySendReplyMsg(messageChainOf(PlainText("需要私聊执行这个指令")),subject, replyMeta)
    }
    private suspend fun trySendReplyMsg(msgChain: MessageChain, subject:Contact?=null, replyMeta:QuoteReply?=null): MessageReceipt<Contact>? {
        return if (replyMeta!=null){
            subject?.sendMessage(messageChainOf(replyMeta,msgChain))
        } else{
            subject?.sendMessage(msgChain)
        }
    }
}
object PluginData : AutoSavePluginData("index"){
    var indexes:MutableMap<String,String> by value()
}
@Serializable
data class RespObj(
    val images: Array<ImageObj>,
    val tooltips: Map<String,String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RespObj

        if (!images.contentEquals(other.images)) return false

        return true
    }

    override fun hashCode(): Int {
        return images.contentHashCode()
    }
}

@Serializable
data class ImageObj(
    val startdate: String,
    val fullstartdate: String,
    val enddate: String,
    val urlbase: String,
    val copyrightlink: String,
    val title:String,
    val quiz: String,
    val wp: Boolean,
    val hsh: String,
    val drk: Int,
    val top: Int,
    val bot: Int,
    val hs: Array<String>,
    val copyright: String,
    val url: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageObj

        if (startdate != other.startdate) return false
        if (fullstartdate != other.fullstartdate) return false
        if (enddate != other.enddate) return false
        if (urlbase != other.urlbase) return false
        if (copyrightlink != other.copyrightlink) return false
        if (title != other.title) return false
        if (quiz != other.quiz) return false
        if (wp != other.wp) return false
        if (hsh != other.hsh) return false
        if (drk != other.drk) return false
        if (top != other.top) return false
        if (bot != other.bot) return false
        if (!hs.contentEquals(other.hs)) return false
        if (copyright != other.copyright) return false
        if (url != other.url) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startdate.hashCode()
        result = 31 * result + fullstartdate.hashCode()
        result = 31 * result + enddate.hashCode()
        result = 31 * result + urlbase.hashCode()
        result = 31 * result + copyrightlink.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + quiz.hashCode()
        result = 31 * result + wp.hashCode()
        result = 31 * result + hsh.hashCode()
        result = 31 * result + drk
        result = 31 * result + top
        result = 31 * result + bot
        result = 31 * result + hs.contentHashCode()
        result = 31 * result + copyright.hashCode()
        result = 31 * result + url.hashCode()
        return result
    }
}
