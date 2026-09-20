package cn.sunline.tss.compare;

/** 原任务身份及核对日期；不引入新的任务ID。 */
public record Task(long index, String date, String batch, String timeNode, String verifyDate) {}
