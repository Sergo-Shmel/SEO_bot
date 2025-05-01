import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.*;

public class TelegramArticleBot extends TelegramLongPollingBot {
    // =============== CONFIG ================
    private static final String BOT_TOKEN       = "7674387942:AAFBJ0KGEBqG-uXFuRQyzm7E9-tswWX-kq4";
    private static final String BOT_USERNAME    = "SeoCreatorBot";
    private static final String N8N_WEBHOOK_URL = "https://vaierii21061.app.n8n.cloud/webhook/4b2dae6e-801a-49b8-8839-2c9c8f73f098";
    private static final String CHANNEL_ID      = "@mirAl_iielvani";
    private static final String GOOGLE_DOCS_URL_PREFIX = "https://docs.google.com/document/d/";
    // =======================================

    private enum ChannelType { TG, SITE }
    private enum ActionType { GENERATE, REWRITE }

    private static class UserState {
        ChannelType channel;
        ActionType action;
        String topic;
        String description;
        boolean awaitingOriginal;
        boolean awaitingFeedback;
        String originalText;
        String zenDocumentId;
    }

    private static class ArticleResult {
        String text, picture;
        String zenDocumentId;
        ArticleResult(String t, String p) { text = t; picture = p; }
        ArticleResult(String docId) { zenDocumentId = docId; }
    }

    private final Map<Long, UserState> states = new HashMap<>();
    private final Map<Long, ArticleResult> lastResults = new HashMap<>();
    private final Set<Long> greeted = new HashSet<>();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofMinutes(2))
            .readTimeout(java.time.Duration.ofMinutes(2))
            .build();

    private void resetUserState(long chatId) {
        states.remove(chatId);
        lastResults.remove(chatId);
        greeted.remove(chatId);
    }

    @Override
    public void onUpdateReceived(Update upd) {
        if (upd.hasMessage() && upd.getMessage().hasText() && upd.getMessage().getText().equals("/start")) {
            long chatId = upd.getMessage().getChatId();
            resetUserState(chatId);
            sendWelcome(chatId);
            return;
        }

        if (upd.hasCallbackQuery()) {
            try {
                handleCallback(upd.getCallbackQuery());
            } catch(Exception e) {
                e.printStackTrace();
                sendPlatformChoice(upd.getCallbackQuery().getMessage().getChatId());
            }
        } else if (upd.hasMessage() && upd.getMessage().hasText()) {
            handleText(upd.getMessage());
        }
    }

    private void handleCallback(CallbackQuery cb) throws Exception {
        long chat = cb.getMessage().getChatId();
        int msgId = cb.getMessage().getMessageId();
        String data = cb.getData();
        UserState st = states.get(chat);
        ArticleResult ar = lastResults.get(chat);

        if ("VIEW".equals(data)) {
            if (ar != null && ar.zenDocumentId != null) {
                String url = GOOGLE_DOCS_URL_PREFIX + ar.zenDocumentId;
                InlineKeyboardButton back = new InlineKeyboardButton("🔙 Назад");
                back.setCallbackData("BACK");
                InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                        Collections.singletonList(Collections.singletonList(back))
                );
                sendMessage(chat, "🔗 Ссылка на статью в Google Docs: " + url, kb);
            }
            return;
        }

        if ("BACK".equals(data)) {
            execute(new DeleteMessage(String.valueOf(chat), msgId));
            if (ar != null) {
                if (ar.zenDocumentId != null) {
                    sendZenArticleButtons(chat, ar);
                } else {
                    sendArticleWithButtons(chat, ar);
                }
            }
            return;
        }

        if ("MAIN_MENU".equals(data)) {
            execute(new DeleteMessage(String.valueOf(chat), msgId));
            resetUserState(chat);
            sendPlatformChoice(chat);
            return;
        }

        execute(new DeleteMessage(String.valueOf(chat), msgId));

        if ("PUBLISH".equals(data)) {
            if (ar != null) {
                if (ar.zenDocumentId != null) {
                    sendToDzen(ar.zenDocumentId);
                } else {
                    sendToChannel(ar.text, ar.picture);
                }
                sendText(chat, "✅ Опубликовано!");
            }
            resetUserState(chat);
            sendPlatformChoice(chat);
            return;
        }

        if ("REREWRITE".equals(data)) {
            if (ar == null) {
                sendText(chat, "❌ Сначала сгенерируйте статью.");
                sendPlatformChoice(chat);
                return;
            }
            UserState newState = new UserState();
            newState.channel = (ar.zenDocumentId != null) ? ChannelType.SITE : ChannelType.TG;
            newState.action = ActionType.REWRITE;
            newState.awaitingFeedback = true;
            newState.originalText = (ar.zenDocumentId != null)
                    ? ar.zenDocumentId
                    : (ar.text != null ? ar.text : "");
            states.put(chat, newState);
            sendText(chat, "✏️ Что нужно изменить в статье?");
            return;
        }

        if (data.startsWith("CH_")) {
            UserState newState = new UserState();
            newState.channel = data.equals("CH_TG") ? ChannelType.TG : ChannelType.SITE;
            states.put(chat, newState);
            sendActionMenu(chat, newState.channel);
            return;
        }

        if (data.startsWith("ACT_")) {
            if (st == null) {
                sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
                sendPlatformChoice(chat);
                return;
            }
            st.action = data.equals("ACT_GEN") ? ActionType.GENERATE : ActionType.REWRITE;
            if (st.action == ActionType.GENERATE) {
                sendText(chat, "📝 Введите тему статьи:");
            } else {
                st.awaitingOriginal = true;
                sendText(chat, "🔄 Пришлите статью для рерайта:");
            }
        }
    }

    private void handleText(Message msg) {
        long chat = msg.getChatId();
        String txt = msg.getText();
        UserState st = states.get(chat);

        if (txt.equalsIgnoreCase("Главное меню")) {
            resetUserState(chat);
            sendPlatformChoice(chat);
            return;
        }

        if (st == null) {
            if (!greeted.contains(chat)) {
                sendWelcome(chat);
                greeted.add(chat);
            } else {
                sendPlatformChoice(chat);
            }
            return;
        }

        if (st.awaitingOriginal) {
            st.originalText = txt;
            st.awaitingOriginal = false;
            st.awaitingFeedback = true;
            sendText(chat, "✏️ Укажите, что изменить:");
            return;
        }

        if (st.awaitingFeedback) {
            sendText(chat, "⏳ Переписываю...");
            ArticleResult ar = callRewrite(chat, st.originalText, txt);
            if (ar == null) {
                sendText(chat, "❌ Ошибка при рерайте.");
                sendPlatformChoice(chat);
            } else {
                lastResults.put(chat, ar);
                if (ar.zenDocumentId != null) {
                    sendZenArticleButtons(chat, ar);
                } else {
                    sendArticleWithButtons(chat, ar);
                }
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
                sendText(chat, "⏳ Генерирую...");
                ArticleResult ar = fetchFromN8n(chat, st);
                if (ar == null) {
                    sendText(chat, "❌ Ошибка генерации.");
                    sendPlatformChoice(chat);
                } else {
                    lastResults.put(chat, ar);
                    if (ar.zenDocumentId != null) {
                        sendZenArticleButtons(chat, ar);
                    } else {
                        sendArticleWithButtons(chat, ar);
                    }
                }
            }
        }
    }

    private void sendWelcome(long chat) {
        sendPlatformChoice(chat);
    }

    private void sendPlatformChoice(long chat) {
        InlineKeyboardButton tg = new InlineKeyboardButton("📱 Telegram");
        tg.setCallbackData("CH_TG");
        InlineKeyboardButton dz = new InlineKeyboardButton("🌐 Дзен");
        dz.setCallbackData("CH_SITE");
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(tg),
                        Collections.singletonList(dz)
                )
        );
        sendMessage(chat, "Выбери площадку:", kb);
    }

    private void sendActionMenu(long chat, ChannelType ch) {
        InlineKeyboardButton gen = new InlineKeyboardButton("📝 Генерировать");
        gen.setCallbackData("ACT_GEN");
        InlineKeyboardButton rew = new InlineKeyboardButton("✍️ Переписать");
        rew.setCallbackData("ACT_REWRITE");
        String where = (ch == ChannelType.TG) ? "Telegram" : "Дзен";
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(gen),
                        Collections.singletonList(rew)
                )
        );
        sendMessage(chat, "Что делаем с " + where + "?", kb);
    }

    private void sendArticleWithButtons(long chat, ArticleResult ar) {
        InlineKeyboardButton re = new InlineKeyboardButton("✍️ Переписать");
        re.setCallbackData("REREWRITE");
        InlineKeyboardButton pu = new InlineKeyboardButton("🚀 Запостить");
        pu.setCallbackData("PUBLISH");

        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(re),
                        Collections.singletonList(pu)
                )
        );
        if (ar.picture != null && !ar.picture.isEmpty()) {
            SendPhoto ph = new SendPhoto();
            ph.setChatId(String.valueOf(chat));
            ph.setPhoto(new InputFile(ar.picture));
            String full = ar.text;
            ph.setCaption(full.length() <= 1024 ? full : full.substring(0, 1024));
            ph.setReplyMarkup(kb);
            safeSend(ph);
            if (full.length() > 1024) {
                sendText(chat, full.substring(1024));
            }
        } else {
            SendMessage m = new SendMessage(String.valueOf(chat), ar.text);
            m.setReplyMarkup(kb);
            executeSilently(m);
        }
    }

    private void sendZenArticleButtons(long chat, ArticleResult ar) {
        InlineKeyboardButton view = new InlineKeyboardButton("👀 Посмотреть");
        view.setUrl(GOOGLE_DOCS_URL_PREFIX + ar.zenDocumentId);

        InlineKeyboardButton re = new InlineKeyboardButton("✍️ Переписать");
        re.setCallbackData("REREWRITE");

//        InlineKeyboardButton pu = new InlineKeyboardButton("🚀 Запостить");
//        pu.setCallbackData("PUBLISH");



        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                Arrays.asList(
                        Collections.singletonList(view),
                        Collections.singletonList(re)
//                        Arrays.asList(re, pu)
                )
        );
        sendMessage(chat, "✅ Статья готова! Вы можете:", kb);
    }

    private ArticleResult fetchFromN8n(long chat, UserState st) {
        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"channel\":\"%s\",\"action\":\"%s\"," +
                        "\"topic\":\"%s\",\"description\":\"%s\"}",
                chat, st.channel.name().toLowerCase(),
                st.action.name().toLowerCase(),
                escape(st.topic), escape(st.description)
        );
        return callN8n(payload, st.channel);
    }

    private ArticleResult callRewrite(long chat, String orig, String fb) {
        UserState st = states.get(chat);
        if (st == null) {
            sendText(chat, "❌ Ошибка состояния. Попробуйте заново.");
            sendPlatformChoice(chat);
            return null;
        }
        ChannelType channel = st.channel;

        String payload = String.format(Locale.ROOT,
                "{\"chat_id\":%d,\"channel\":\"%s\",\"action\":\"rewrite\"," +
                        "\"original\":\"%s\",\"feedback\":\"%s\"}",
                chat,
                channel.name().toLowerCase(),
                escape(orig),
                escape(fb)
        );
        return callN8n(payload, channel);
    }

    private ArticleResult callN8n(String payload, ChannelType channel) {
        System.out.println("→ n8n payload: " + payload);
        RequestBody body = RequestBody.create(
                payload, MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder().url(N8N_WEBHOOK_URL).post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            String s = resp.body() != null ? resp.body().string() : null;
            System.out.println("← n8n response: " + s);
            if (!resp.isSuccessful() || s == null) return null;

            if (channel == ChannelType.SITE) {
                JSONArray jsonArray = new JSONArray(s);
                if (jsonArray.length() > 0) {
                    JSONObject j = jsonArray.getJSONObject(0);
                    if (j.has("documentId")) {
                        return new ArticleResult(j.getString("documentId"));
                    }
                }
                return null;
            } else {
                JSONObject j = new JSONObject(s);
                return new ArticleResult(j.optString("text"), j.optString("picture"));
            }
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

    private void sendToDzen(String documentId) {
        JSONObject payload = new JSONObject();
        payload.put("action", "publish");
        payload.put("documentId", documentId);

        RequestBody body = RequestBody.create(
                payload.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request req = new Request.Builder()
                .url(N8N_WEBHOOK_URL + "zen-publish")
                .post(body).build();
        try (Response resp = http.newCall(req).execute()) {
            System.out.println("→ Zen publish response: " + (resp.body() != null ? resp.body().string() : "null"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private void sendText(long chat, String t) {
        executeSilently(new SendMessage(String.valueOf(chat), t));
    }

    private void sendMessage(long chat, String t, InlineKeyboardMarkup kb) {
        SendMessage m = new SendMessage(String.valueOf(chat), t);
        m.setReplyMarkup(kb);
        executeSilently(m);
    }

    private void executeSilently(SendMessage m) {
        try { execute(m); } catch (Exception e) { e.printStackTrace(); }
    }

    private void safeSend(SendPhoto p) {
        try { execute(p); } catch (Exception e) { e.printStackTrace(); }
    }

    @Override public String getBotUsername() { return BOT_USERNAME; }
    @Override public String getBotToken() { return BOT_TOKEN; }

    public static void main(String[] args) throws Exception {
        new TelegramBotsApi(DefaultBotSession.class)
                .registerBot(new TelegramArticleBot());
        System.out.println("Bot started");
    }
}