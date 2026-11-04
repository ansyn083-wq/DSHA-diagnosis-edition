package com.deepseekharness.app.util;

import java.util.List;

/** 原生空白会话问候：本地时间分组、不连续重复，并可按稳定 ID 恢复。 */
public final class NativeChatGreeting {
  public record Choice(String id, String chinese, String english) {}

  private static final List<Choice> GENERAL =
      List.of(
          new Choice("general-0", "今天想聊点什么？", "What would you like to talk about today?"),
          new Choice("general-1", "有什么想一起梳理的？", "What would you like to work through?"),
          new Choice("general-2", "从一个想法开始吧。", "Let's start with an idea."),
          new Choice(
              "general-3", "需要查资料，还是理思路？", "Looking for information or sorting out an idea?"),
          new Choice("general-4", "想解决什么问题？", "What would you like to figure out?"),
          new Choice("general-5", "有新想法？可以从这里开始。", "Have an idea? Start here."),
          new Choice("general-6", "把问题交给我，一起看看。", "Share your question and let's take a look."),
          new Choice("general-7", "接下来想做点什么？", "What would you like to do next?"),
          new Choice("general-8", "有个难题卡住了？", "Stuck on a tricky problem?"),
          new Choice(
              "general-9",
              "手头有份草稿？我们可以一起看看。",
              "Have a draft handy? We can go through it together."),
          new Choice("general-10", "最近对什么好奇？", "What have you been curious about lately?"),
          new Choice("general-11", "有几个选项拿不定主意？", "Weighing a few options?"),
          new Choice("general-12", "有什么想弄懂的概念？", "Is there a concept you'd like to understand?"),
          new Choice("general-13", "想把哪个想法变成计划？", "Which idea would you like to turn into a plan?"),
          new Choice(
              "general-14", "在写代码？可以从遇到的问题说起。", "Writing code? Tell me where you got stuck."),
          new Choice("general-15", "刚看到什么有意思的东西？", "Come across anything interesting?"));
  private static final List<Choice> MORNING =
      List.of(
          new Choice("morning-0", "早上好，今天从哪里开始？", "Good morning. Where shall we start?"),
          new Choice("morning-1", "早上好，有什么想了解的？", "Good morning. What would you like to explore?"),
          new Choice("morning-2", "新的一天，想先做什么？", "A new day. What would you like to do first?"),
          new Choice("morning-3", "早安，来聊聊今天的想法。", "Good morning. What's on your mind today?"),
          new Choice(
              "morning-4",
              "早上好，今天最想搞懂哪件事？",
              "Good morning. What's one thing you'd like to understand today?"),
          new Choice(
              "morning-5",
              "早安，要接着昨天的想法聊吗？",
              "Morning. Want to pick up where you left off yesterday?"),
          new Choice(
              "morning-6", "早上好，要不要先列个今天的小计划？", "Good morning. Want to sketch a plan for today?"),
          new Choice(
              "morning-7",
              "早安，今天有什么想试试的？",
              "Morning. Is there something you'd like to try today?"));
  private static final List<Choice> AFTERNOON =
      List.of(
          new Choice(
              "afternoon-0", "下午好，想聊点什么？", "Good afternoon. What would you like to chat about?"),
          new Choice("afternoon-1", "下午好，有什么需要帮忙的？", "Good afternoon. How can I help?"),
          new Choice(
              "afternoon-2", "下午好，接下来想做什么？", "Good afternoon. What would you like to do next?"),
          new Choice("afternoon-3", "来整理一下下午的思路吧。", "Let's work through an idea this afternoon."),
          new Choice(
              "afternoon-4", "下午好，手头有什么需要理一理？", "Good afternoon. What are you working through?"),
          new Choice(
              "afternoon-5",
              "下午好，要不要一起看看刚遇到的问题？",
              "Good afternoon. Want to look at a problem you just ran into?"),
          new Choice(
              "afternoon-6",
              "下午好，有什么想趁现在弄明白？",
              "Good afternoon. Anything you'd like to figure out while you have a moment?"),
          new Choice(
              "afternoon-7",
              "下午好，今天的计划走到哪一步了？",
              "Good afternoon. How's today's plan coming along?"));
  private static final List<Choice> EVENING =
      List.of(
          new Choice("evening-0", "晚上好，今天有什么新想法？", "Good evening. Any new ideas today?"),
          new Choice("evening-1", "晚上好，想从哪里聊起？", "Good evening. Where would you like to start?"),
          new Choice(
              "evening-2", "晚上好，有什么想一起看看？", "Good evening. What would you like to look into?"),
          new Choice("evening-3", "想回顾今天，还是聊点新的？", "Review the day, or explore something new?"),
          new Choice("evening-4", "晚上好，今天哪件事让你印象最深？", "Good evening. What stood out to you today?"),
          new Choice(
              "evening-5",
              "晚上好，有没有想留到明天继续的事？",
              "Good evening. Anything you'd like to pick up tomorrow?"),
          new Choice(
              "evening-6",
              "晚上好，想整理一下今天学到的东西吗？",
              "Good evening. Want to go over what you learned today?"),
          new Choice(
              "evening-7",
              "晚上好，手头还有什么想收个尾？",
              "Good evening. Is there anything you'd like to wrap up?"));
  private static final List<Choice> NIGHT =
      List.of(
          new Choice("night-0", "夜深了，想聊点什么？", "It's late. What would you like to talk about?"),
          new Choice("night-1", "这个时间，有什么想了解的？", "What would you like to explore at this hour?"),
          new Choice("night-2", "夜里也可以慢慢理清思路。", "We can take our time working through an idea."),
          new Choice(
              "night-3", "有个问题想弄明白？一起看看。", "A question you'd like to clear up? Let's take a look."),
          new Choice(
              "night-4", "夜深了，要不要把脑子里的想法先记下来？", "It's late. Want to jot down what's on your mind?"),
          new Choice("night-5", "还没休息？想聊聊什么？", "Still up? What would you like to talk about?"),
          new Choice("night-6", "有个想法突然冒出来了？", "Did an idea just come to you?"),
          new Choice("night-7", "想趁安静的时候读点什么？", "Feel like reading something while it's quiet?"));

  private NativeChatGreeting() {}

  /** 显式传入随机值，确保选择逻辑可重现；仅新开窗口或新对话时调用。 */
  public static Choice choose(int localHour, long entropy, String previousId) {
    List<Choice> timed = timed(localHour);
    int count = GENERAL.size() + timed.size();
    int index = (int) Math.floorMod(entropy, (long) count);
    Choice chosen = at(index, timed);
    if (chosen.id().equals(previousId)) chosen = at((index + 1) % count, timed);
    return chosen;
  }

  /** 旋转保持文案；跨时段后不会恢复过时的早安或晚安。 */
  public static Choice restore(int localHour, String id) {
    List<Choice> timed = timed(localHour);
    for (Choice choice : GENERAL) if (choice.id().equals(id)) return choice;
    for (Choice choice : timed) if (choice.id().equals(id)) return choice;
    return null;
  }

  private static Choice at(int index, List<Choice> timed) {
    return index < GENERAL.size() ? GENERAL.get(index) : timed.get(index - GENERAL.size());
  }

  private static List<Choice> timed(int hour) {
    if (hour < 0 || hour > 23)
      throw new IllegalArgumentException("Local hour must be between 0 and 23");
    if (hour >= 5 && hour < 12) return MORNING;
    if (hour >= 12 && hour < 18) return AFTERNOON;
    if (hour >= 18 && hour < 23) return EVENING;
    return NIGHT;
  }
}
