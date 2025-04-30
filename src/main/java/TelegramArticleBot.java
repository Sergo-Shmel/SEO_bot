import okhttp3.*;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.*;

public class TelegramArticleBot extends TelegramLongPollingBot {
    // =============== CONFIG ================
    private static final String BOT_TOKEN       = "7674387942:AAFBJ0KGEBqG-uXFuRQyzm7E9-tswWX-kq4";
    private static final String BOT_USERNAME    = "SeoCreatorBot";
    private static final String N8N_WEBHOOK_URL = "https://vaierii21061.app.n8n.cloud/webhook/4b2dae6e-801a-49b8-8839-2c9c8f73f098";
    private static final String CHANNEL_ID      = "@mirAl_iielvani";
    // =======================================

    private enum ChannelType { TG, SITE }
    private enum ActionType  { GENERATE, REWRITE }

    private static class UserState {
        ChannelType channel;
        ActionType  action;
        String      topic;
        String      description;
        boolean     awaitingOriginal;
        boolean     awaitingFeedback;
        String      originalText;
    }

    private static class ArticleResult {
        String text, picture;
        ArticleResult(String t, String p) { text = t; picture = p; }
    }

    private final Map<Long,UserState>     states      = new HashMap<>();
    private final Map<Long,ArticleResult> lastResults = new HashMap<>();
    private final Set<Long>               greeted     = new HashSet<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofMinutes(2))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .build();

    @Override
    public void onUpdateReceived(Update upd) {
        if (upd.hasCallbackQuery()) {
            try { handleCallback(upd.getCallbackQuery()); }
            catch(Exception e){ e.printStackTrace(); }
        } else if (upd.hasMessage() && upd.getMessage().hasText()) {
            handleText(upd.getMessage());
        }
    }

    private void handleCallback(CallbackQuery cb) throws Exception {
        long   chat  = cb.getMessage().getChatId();
        int    msgId = cb.getMessage().getMessageId();
        String data  = cb.getData();
        execute(new DeleteMessage(String.valueOf(chat), msgId));

        if ("PUBLISH".equals(data)) {
            ArticleResult ar = lastResults.get(chat);
            if (ar != null) sendToChannel(ar.text, ar.picture);
            sendText(chat, "✅ Статья выложена!");
            sendNextMenu(chat);
            states.remove(chat);
            lastResults.remove(chat);
            return;
        }

        if ("REREWRITE".equals(data)) {
            ArticleResult ar = lastResults.get(chat);
            if (ar == null) {
                sendText(chat, "❌ Сначала сгенерируйте статью.");
                sendNextMenu(chat);
                return;
            }
            UserState st = new UserState();
            st.channel          = ChannelType.TG;
            st.action           = ActionType.REWRITE;
            st.awaitingFeedback = true;
            st.originalText     = ar.text;
            states.put(chat, st);
            sendText(chat, "✏️ Что нужно изменить в сгенерированной статье?");
            return;
        }

        if (data.startsWith("CH_")) {
            UserState st = new UserState();
            st.channel = data.equals("CH_TG") ? ChannelType.TG : ChannelType.SITE;
            states.put(chat, st);
            sendActionMenu(chat, st.channel);
            return;
        }

        if (data.startsWith("ACT_")) {
            UserState st = states.get(chat);
            if (st == null) {
                sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
                sendNextMenu(chat);
                return;
            }
            st.action = data.equals("ACT_GEN") ? ActionType.GENERATE : ActionType.REWRITE;
            if (st.action == ActionType.GENERATE) {
                sendText(chat, "📝 Введите тему статьи:");
            } else {
                if (st.channel == ChannelType.TG) {
                    st.awaitingOriginal = true;
                    sendText(chat, "🔄 Пришлите статью, которую нужно переписать:");
                } else {
                    sendText(chat, "❌ Авто-рерайт доступен только в Telegram.");
                    sendNextMenu(chat);
                    states.remove(chat);
                }
            }
        }
    }

    private void handleText(Message msg) {
        long      chat = msg.getChatId();
        String    txt  = msg.getText();
        UserState st   = states.get(chat);

        if (st == null) {
            if (!greeted.contains(chat)) {
                sendWelcome(chat);
                greeted.add(chat);
            } else {
                sendNextMenu(chat);
            }
            return;
        }

        if (st.awaitingOriginal) {
            st.originalText     = txt;
            st.awaitingOriginal = false;
            st.awaitingFeedback = true;
            sendText(chat, "✏️ Теперь укажите, что нужно изменить:");
            return;
        }

        if (st.awaitingFeedback) {
            sendText(chat, "⏳ Переписываю…");
            ArticleResult ar = callRewrite(chat, st.originalText, txt);
            if (ar == null) {
                sendText(chat, "❌ Ошибка при рерайте.");
                sendNextMenu(chat);
            } else {
                lastResults.put(chat, ar);
                sendArticleWithButtons(chat, ar);
            }
            states.remove(chat);
            return;
        }

        if (st.action == ActionType.GENERATE) {
            if (st.topic == null) {
                st.topic = txt;
                sendText(chat, "📝 Опишите подробнее:");
                return;
            }
            if (st.description == null) {
                st.description = txt;
                sendText(chat, "⏳ Генерирую…");
                ArticleResult ar = fetchFromN8n(chat, st);
                if (ar == null) {
                    sendText(chat, "❌ Ошибка генерации.");
                    sendNextMenu(chat);
                } else {
                    lastResults.put(chat, ar);
                    sendArticleWithButtons(chat, ar);
                }
            }
        }
    }

    private void sendWelcome(long chat) {
        InlineKeyboardButton tg = new InlineKeyboardButton("📱 Telegram");
        tg.setCallbackData("CH_TG");
        SendMessage m = new SendMessage(String.valueOf(chat), "Привет! Выберите площадку:");
        m.setReplyMarkup(new InlineKeyboardMarkup(
                Collections.singletonList(Collections.singletonList(tg))
        ));
        executeSilently(m);
    }

    private void sendNextMenu(long chat) {
        InlineKeyboardButton tg = new InlineKeyboardButton("📱 Telegram");
        tg.setCallbackData("CH_TG");
        SendMessage m = new SendMessage(String.valueOf(chat), "Что делаем дальше?");
        m.setReplyMarkup(new InlineKeyboardMarkup(
                Collections.singletonList(Collections.singletonList(tg))
        ));
        executeSilently(m);
    }

    private void sendActionMenu(long chat, ChannelType ch) {
        InlineKeyboardButton gen = new InlineKeyboardButton("📝 Генерировать");
        gen.setCallbackData("ACT_GEN");
        InlineKeyboardButton rew = new InlineKeyboardButton("✍️ Переписать");
        rew.setCallbackData("ACT_REWRITE");
        String where = ch == ChannelType.TG ? "Telegram" : "сайт/Дзен";
        SendMessage m = new SendMessage(String.valueOf(chat), "Что делаем с " + where + "?");
        m.setReplyMarkup(new InlineKeyboardMarkup(Arrays.asList(
                Collections.singletonList(gen),
                Collections.singletonList(rew)
        )));
        executeSilently(m);
    }

    private void sendArticleWithButtons(long chat, ArticleResult ar) {
        InlineKeyboardButton re = new InlineKeyboardButton("✍️ Переписать");
        re.setCallbackData("REREWRITE");
        InlineKeyboardButton pu = new InlineKeyboardButton("🚀 Запостить");
        pu.setCallbackData("PUBLISH");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(Arrays.asList(
                Collections.singletonList(re),
                Collections.singletonList(pu)
        ));
        if (ar.picture != null && !ar.picture.isEmpty()) {
            SendPhoto ph = new SendPhoto();
            ph.setChatId(String.valueOf(chat));
            ph.setPhoto(new InputFile(ar.picture));
            String full = ar.text;
            ph.setCaption(full.length() <= 1024 ? full : full.substring(0, 1024));
            ph.setReplyMarkup(kb);
            safeSend(ph);
            if (full.length() > 1024) sendText(chat, full.substring(1024));
        } else {
            SendMessage m = new SendMessage(String.valueOf(chat), ar.text);
            m.setReplyMarkup(kb);
            executeSilently(m);
        }
    }

    private ArticleResult fetchFromN8n(long chat, UserState st) {
        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"channel\":\"%s\",\"action\":\"%s\"," +
                        "\"topic\":\"%s\",\"description\":\"%s\"}",
                chat, st.channel.name().toLowerCase(), st.action.name().toLowerCase(),
                escape(st.topic), escape(st.description)
        );
        return callN8n(payload);
    }

    private ArticleResult callRewrite(long chat, String orig, String fb) {
        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"channel\":\"tg\",\"action\":\"rewrite\"," +
                        "\"original\":\"%s\",\"feedback\":\"%s\"}",
                chat, escape(orig), escape(fb)
        );
        return callN8n(payload);
    }

    private ArticleResult callN8n(String payload) {
        System.out.println("→ n8n payload: " + payload);
        RequestBody body = RequestBody.create(
                payload, MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(N8N_WEBHOOK_URL).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String s = resp.body() != null ? resp.body().string() : null;
            System.out.println("← n8n response: " + s);
            if (!resp.isSuccessful() || s == null) return null;
            JSONObject j = new JSONObject(s);
            return new ArticleResult(j.optString("text"), j.optString("picture"));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendToChannel(String text, String pic) {
        if (pic != null && !pic.isEmpty()) {
            SendPhoto ph = new SendPhoto();
            ph.setChatId(CHANNEL_ID);
            ph.setPhoto(new InputFile(pic));
            ph.setCaption(text);
            safeSend(ph);
        } else {
            sendText(Long.parseLong(CHANNEL_ID), text);
        }
    }

    private static String escape(String s) {
        // экранируем " \ и новые строки
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                ;
    }

    private void sendText(long chat, String t) {
        executeSilently(new SendMessage(String.valueOf(chat), t));
    }
    private void executeSilently(SendMessage m) {
        try { execute(m); } catch (Exception e) { e.printStackTrace(); }
    }
    private void safeSend(SendPhoto p) {
        try { execute(p); } catch (Exception e) { e.printStackTrace(); }
    }

    @Override public String getBotUsername() { return BOT_USERNAME; }
    @Override public String getBotToken()   { return BOT_TOKEN; }

    public static void main(String[] args) throws Exception {
        new TelegramBotsApi(DefaultBotSession.class)
                .registerBot(new TelegramArticleBot());
        System.out.println("Bot started");
    }
}
