package com.yepplayer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg=Color.rgb(12,18,30); private val panel=Color.rgb(30,42,62); private val white=Color.WHITE
    private val pool=Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var profile: Profile?=null
    private var kind="live"
    private var items=listOf<MediaItem>()
    private val prefs by lazy { getSharedPreferences("yepplayer",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); profile=readProfile(); home() }
    private fun text(s:String,size:Float=16f):TextView = TextView(this).apply { text=s; textSize=size; setTextColor(white); setPadding(16,13,16,13) }
    private fun button(s:String, onClick:()->Unit): Button = Button(this).apply { text=s; setTextColor(white); setBackgroundColor(panel); isAllCaps=false; setOnClickListener { onClick() } }
    private fun layout(title:String) {
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(12,18,12,8) }
        setContentView(root)
        root.addView(text("▶ YepPlayer  •  $title",24f))
    }
    private fun scroll():LinearLayout {
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1,0,1f))
        return content
    }
    private fun readProfile():Profile? = try { val j=JSONObject(prefs.getString("profile", "")!!); Profile(j.getString("type"),j.getString("base"),j.optString("username"),j.optString("password")) } catch (_:Exception) { null }
    private fun saveProfile(p:Profile) { prefs.edit().putString("profile",JSONObject().put("type",p.type).put("base",p.base).put("username",p.username).put("password",p.password).toString()).apply(); profile=p }
    private fun home() {
        layout("Home")
        val c=scroll()
        c.addView(text(if(profile==null) "Connect your authorised IPTV source" else "Source connected: ${profile!!.type.uppercase()}",17f))
        c.addView(button("⚙ Add / change playlist") { configure() })
        c.addView(button("📺 Live TV") { load("live") })
        c.addView(button("🎬 Movies") { load("vod") })
        c.addView(button("▣ Series") { load("series") })
        c.addView(button("★ Favourites") { showItems(items.filter { isFavourite(it) },"Favourites") })
        c.addView(button("🔒 Set parental PIN") { changePin() })
        c.addView(text("Use only streams and playlists you are authorised to access.",12f))
    }
    private fun configure() {
        layout("Connect source")
        val c=scroll()
        c.addView(text("Xtream Codes",20f))
        val server=EditText(this).apply { hint="https://provider.example:port"; setSingleLine(true); setText(profile?.takeIf{it.type=="xtream"}?.base ?: "") }
        val user=EditText(this).apply { hint="Username"; setSingleLine(true) }
        val pass=EditText(this).apply { hint="Password"; setSingleLine(true); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        c.addView(server); c.addView(user); c.addView(pass)
        c.addView(button("Save Xtream account") { if(server.text.isBlank()||user.text.isBlank()||pass.text.isBlank()) toast("Fill in all fields") else { saveProfile(Profile("xtream",server.text.toString(),user.text.toString(),pass.text.toString())); home() } })
        c.addView(text("Or use an M3U URL",20f))
        val m3u=EditText(this).apply { hint="https://example.com/playlist.m3u"; setSingleLine(true) }
        c.addView(m3u)
        c.addView(button("Save M3U URL") { if(!m3u.text.startsWith("http")) toast("Enter an HTTP(S) URL") else { saveProfile(Profile("m3u",m3u.text.toString())); home() } })
        c.addView(button("← Back") { home() })
    }
    private fun load(type:String) {
        val p=profile ?: run { configure(); return }
        kind=type; layout("Loading ${type.uppercase()}…")
        pool.execute { try { val loaded=Source.load(p,type); runOnUiThread { items=loaded; showItems(loaded,type.uppercase()) } } catch(e:Exception) { runOnUiThread { layout("Connection error"); root.addView(text(e.message ?: "Unable to load source")); root.addView(button("Back") { home() }) } } }
    }
    private fun showItems(data:List<MediaItem>,title:String) {
        layout(title)
        val top=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        top.addView(button("⌂ Home") { home() },LinearLayout.LayoutParams(0,-2,1f))
        top.addView(button("★ Favourites") { showItems(items.filter { isFavourite(it) },"Favourites") },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(top)
        val categories=listOf("All")+data.map{it.category}.distinct().sorted()
        val filter=Spinner(this)
        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,categories)
        root.addView(filter)
        val search=EditText(this).apply { hint="Search"; setSingleLine(true); setTextColor(white); setHintTextColor(Color.LTGRAY) }
        root.addView(search)
        val list=scroll()
        fun render() {
            list.removeAllViews()
            val selected=categories[filter.selectedItemPosition.coerceAtLeast(0)]
            val visible=data.filter { (selected=="All"||it.category==selected) && it.name.contains(search.text.toString(),true) }.take(400)
            list.addView(text("${visible.size} shown / ${data.size} items",12f))
            visible.forEach { item ->
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
                row.addView(button(item.name.take(48)) { open(item) },LinearLayout.LayoutParams(0,-2,1f))
                row.addView(button(if(isFavourite(item)) "★" else "☆") { toggleFavourite(item); render() })
                list.addView(row)
            }
        }
        filter.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long) { render() }
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
        }
        search.addTextChangedListener(object:android.text.TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()}; override fun afterTextChanged(s:android.text.Editable?){} })
        render()
    }
    private fun favKey(item:MediaItem)="${profile?.base}|${item.kind}|${item.id}"
    private fun isFavourite(item:MediaItem)=prefs.getStringSet("favs",emptySet())!!.contains(favKey(item))
    private fun toggleFavourite(item:MediaItem) { val set=prefs.getStringSet("favs",emptySet())!!.toMutableSet(); if(!set.add(favKey(item)))set.remove(favKey(item)); prefs.edit().putStringSet("favs",set).apply() }
    private fun isRestricted(item:MediaItem):Boolean = Regex("adult|xxx|18\\+|porn",RegexOption.IGNORE_CASE).containsMatchIn(item.category+" "+item.name)
    private fun open(item:MediaItem) {
        val pin=prefs.getString("pin",null)
        if(pin!=null && isRestricted(item)) {
            val entry=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            AlertDialog.Builder(this).setTitle("Parental PIN").setView(entry).setPositiveButton("Unlock") { _,_ -> if(entry.text.toString()==pin) openUnlocked(item) else toast("Incorrect PIN") }.setNegativeButton("Cancel",null).show()
        } else openUnlocked(item)
    }
    private fun openUnlocked(item:MediaItem) {
        if(item.kind=="series") {
            val p=profile ?: return
            layout("Loading episodes…")
            pool.execute { try { val eps=Source.episodes(p,item.seriesId); runOnUiThread { showItems(eps,item.name) } } catch(e:Exception) { runOnUiThread { toast(e.message ?: "Episodes unavailable"); home() } } }
            return
        }
        val actions=mutableListOf("Play")
        if(item.kind=="live" && profile?.type=="xtream") { actions.add("Programme guide"); if(item.catchup)actions.add("Catch-up") }
        AlertDialog.Builder(this).setTitle(item.name).setItems(actions.toTypedArray()) { _,which ->
            when(actions[which]) {
                "Play" -> startPlayer(item.url,item.name)
                "Programme guide" -> showEpg(item)
                "Catch-up" -> catchup(item)
            }
        }.show()
    }
    private fun showEpg(item:MediaItem) {
        val p=profile ?: return
        toast("Loading programme guide")
        pool.execute { try { val entries=Source.epg(p,item.id); runOnUiThread { AlertDialog.Builder(this).setTitle(item.name+" • EPG").setMessage(entries.joinToString("\n\n").ifBlank { "No guide data available" }).setPositiveButton("Close",null).show() } } catch(e:Exception) { runOnUiThread { toast("EPG unavailable: ${e.message}") } } }
    }
    private fun catchup(item:MediaItem) {
        val p=profile ?: return
        val input=EditText(this).apply { hint="YYYY-MM-DD HH:MM (provider timezone)"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Catch-up start time").setMessage("Enter the archive start time. Playback uses a 60-minute window. Availability depends on your provider.").setView(input).setPositiveButton("Play") { _,_ ->
            val start=input.text.toString().trim()
            if(!Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}").matches(start)) { toast("Use YYYY-MM-DD HH:MM"); return@setPositiveButton }
            val url="${Source.base(p)}/timeshift/${java.net.URLEncoder.encode(p.username,"UTF-8")}/${java.net.URLEncoder.encode(p.password,"UTF-8")}/60/${java.net.URLEncoder.encode(start,"UTF-8")}/${item.id}.ts"
            startPlayer(url,item.name+" • Catch-up")
        }.setNegativeButton("Cancel",null).show()
    }
    private fun startPlayer(url:String,name:String) { startActivity(Intent(this,PlayerActivity::class.java).putExtra("url",url).putExtra("name",name)) }
    private fun changePin() {
        val entry=EditText(this).apply { inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint="4–8 digits" }
        AlertDialog.Builder(this).setTitle("Set parental PIN").setMessage("This basic PIN restricts categories labelled adult/XXX/18+. It is not a complete parental filtering system.").setView(entry).setPositiveButton("Save") { _,_ -> val value=entry.text.toString(); if(value.matches(Regex("\\d{4,8}"))) { prefs.edit().putString("pin",value).apply(); toast("PIN saved") } else toast("Use 4–8 digits") }.setNegativeButton("Cancel",null).show()
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }
}
