/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
/* This file has been modified by Open Source Strategies, Inc. */

package org.ofbiz.workeffort.workeffort;

import java.util.List;
import java.util.Set;
import java.util.Stack;

import javolution.util.FastList;
import javolution.util.FastSet;

import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.property.*;

import org.ofbiz.service.calendar.TemporalExpression;
import org.ofbiz.service.calendar.TemporalExpressions;
import org.ofbiz.service.calendar.TemporalExpressionVisitor;
import org.ofbiz.service.calendar.TemporalExpressions.*;

/** Temporal Expression to iCalendar recurrence converter. The conversion results
 * (or conversion success) are unpredictable since the OFBiz Temporal Expressions
 * are more sophisticated than iCalendar recurrences. This class attempts to
 * make a best attempt at conversion and throws <code>IllegalStateException</code>
 * when conversion is not possible.
 */
public class ICalRecurConverter implements TemporalExpressionVisitor {
    protected static final WeekDay dayOfWeekArray[] = {WeekDay.SU, WeekDay.MO, WeekDay.TU, WeekDay.WE, WeekDay.TH, WeekDay.FR, WeekDay.SA};
    protected DtStart dateStart = null;
    protected List<DateListProperty> incDateList = FastList.newInstance();
    protected List<DateListProperty> exDateList = FastList.newInstance();
    protected List<RRule> incRuleList = FastList.newInstance();
    protected List<ExRule> exRuleList = FastList.newInstance();
    protected VisitorState state = new VisitorState();
    protected Stack<VisitorState> stateStack = new Stack<VisitorState>();

    protected ICalRecurConverter() {}

    @SuppressWarnings("unchecked")
    public static void convert(TemporalExpression expr, PropertyList eventProps) {
        ICalRecurConverter converter = new ICalRecurConverter();
        expr.accept(converter);
        DtStart dateStart = (DtStart) eventProps.getProperty(Property.DTSTART);
        if (converter.dateStart != null) {
            if (dateStart != null) {
                eventProps.remove(dateStart);
            }
            dateStart = converter.dateStart;
            eventProps.add(dateStart);
        }
        if (dateStart != null && converter.exRuleList.size() > 0) {
            // iCalendar quirk - if exclusions exist, then the start date must be excluded also
            ExDate exdate = new ExDate();
            exdate.getDates().add(dateStart.getDate());
            converter.exDateList.add(exdate);
        }
        eventProps.addAll(converter.incDateList);
        eventProps.addAll(converter.incRuleList);
        eventProps.addAll(converter.exDateList);
        eventProps.addAll(converter.exRuleList);
    }

    // ----- TemporalExpressionVisitor Implementation ----- //

    public void visit(Null expr) {}

    public void visit(Union expr) {
        for (TemporalExpression childExpr : expr.getExpressionSet()) {
            childExpr.accept(this);
        }
    }

    public void visit(Intersection expr) {
        this.stateStack.push(this.state);
        VisitorState newState = new VisitorState();
        newState.isExcluded = this.state.isExcluded;
        newState.isIntersection = true;
        this.state = newState;
        for (TemporalExpression childExpr : expr.getExpressionSet()) {
            childExpr.accept(this);
        }
        this.state = this.stateStack.pop();
        if (newState.inclRecurList.size() > 0) {
            this.incRuleList.add(new RRule(this.consolidateRecurs(newState.inclRecurList)));
        }
        if (newState.exRecurList.size() > 0) {
            this.exRuleList.add(new ExRule(this.consolidateRecurs(newState.exRecurList)));
        }
    }

    public void visit(Difference expr) {
        VisitorState newState = new VisitorState();
        newState.isIntersection = this.state.isIntersection;
        this.stateStack.push(this.state);
        this.state = newState;
        expr.getIncluded().accept(this);
        newState.isExcluded = true;
        expr.getExcluded().accept(this);
        this.state = this.stateStack.pop();
        if (this.state.isIntersection) {
            this.state.inclRecurList.addAll(newState.inclRecurList);
            this.state.exRecurList.addAll(newState.exRecurList);
        }
    }

    public void visit(TemporalExpressions.DateRange expr) {
        if (this.state.isExcluded) {
            throw new IllegalStateException("iCalendar does not support excluded date ranges");
        }
        org.ofbiz.base.util.DateRange range = expr.getDateRange();
        PeriodList periodList = new PeriodList();
        periodList.add(new Period(new DateTime(range.start()), new DateTime(range.end())));
        this.incDateList.add(new RDate(periodList));
    }

    @SuppressWarnings("unchecked")
    public void visit(TimeOfDayRange expr) {
        // TODO: this needs a better conversion
        int startHr = expr.getStartHours();
        int endHr = expr.getEndHours();
        NumberList hourList = new NumberList();
        hourList.add(startHr);
        while (startHr != endHr) {
            startHr++;
            if (startHr == 24) {
                startHr = 0;
            }
            hourList.add(startHr);
        }
        Recur recur = new Recur(Recur.HOURLY, 0);
        recur.getHourList().addAll(hourList);
        this.state.addRecur(recur);
    }

    @SuppressWarnings("unchecked")
    public void visit(TemporalExpressions.DayOfWeekRange expr) {
        int startDay = expr.getStartDay();
        int endDay = expr.getEndDay();
        WeekDayList dayList = new WeekDayList();
        dayList.add(dayOfWeekArray[startDay - 1]);
        while (startDay != endDay) {
            startDay++;
            if (startDay > java.util.Calendar.SATURDAY) {
                startDay = java.util.Calendar.SUNDAY;
            }
            dayList.add(dayOfWeekArray[startDay - 1]);
        }
        Recur recur = new Recur(Recur.DAILY, 0);
        recur.getDayList().addAll(dayList);
        this.state.addRecur(recur);
    }

    @SuppressWarnings("unchecked")
    public void visit(TemporalExpressions.MonthRange expr) {
        int startMonth = expr.getStartMonth();
        int endMonth = expr.getEndMonth();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int maxMonth = cal.getActualMaximum(java.util.Calendar.MONTH);
        NumberList monthList = new NumberList();
        monthList.add(startMonth + 1);
        while (startMonth != endMonth) {
            startMonth++;
            if (startMonth > maxMonth) {
                startMonth = java.util.Calendar.JANUARY;
            }
            monthList.add(startMonth + 1);
        }
        Recur recur = new Recur(Recur.MONTHLY, 0);
        recur.getMonthList().addAll(monthList);
        this.state.addRecur(recur);
    }

    @SuppressWarnings("unchecked")
    public void visit(TemporalExpressions.DayOfMonthRange expr) {
        int startDay = expr.getStartDay();
        int endDay = expr.getEndDay();
        NumberList dayList = new NumberList();
        dayList.add(startDay);
        while (startDay != endDay) {
            startDay++;
            dayList.add(startDay);
        }
        Recur recur = new Recur(Recur.DAILY, 0);
        recur.getMonthDayList().addAll(dayList);
        this.state.addRecur(recur);
    }

    public void visit(TemporalExpressions.DayInMonth expr) {
        Recur recur = new Recur(Recur.MONTHLY, 0);
        recur.getDayList().add(new WeekDay(dayOfWeekArray[expr.getDayOfWeek() - 1], expr.getOccurrence()));
        this.state.addRecur(recur);
    }

    public void visit(TemporalExpressions.Frequency expr) {
        if (this.dateStart == null) {
            this.dateStart = new DtStart(new net.fortuna.ical4j.model.Date(expr.getStartDate()));
        }
        int freqCount = expr.getFreqCount();
        int freqType = expr.getFreqType();
        switch (freqType) {
        case java.util.Calendar.SECOND:
            this.state.addRecur((new Recur(Recur.SECONDLY, freqCount)));
        case java.util.Calendar.MINUTE:
            this.state.addRecur((new Recur(Recur.MINUTELY, freqCount)));
        case java.util.Calendar.HOUR:
            this.state.addRecur((new Recur(Recur.HOURLY, freqCount)));
        case java.util.Calendar.DAY_OF_MONTH:
            this.state.addRecur((new Recur(Recur.DAILY, freqCount)));
        case java.util.Calendar.MONTH:
            this.state.addRecur((new Recur(Recur.MONTHLY, freqCount)));
        case java.util.Calendar.YEAR:
            this.state.addRecur((new Recur(Recur.YEARLY, freqCount)));
        }
    }

    @SuppressWarnings("unchecked")
    protected Recur consolidateRecurs(List<Recur> recurList) {
        // Try to consolidate a list of Recur instances into one instance
        Set<Integer> monthList = FastSet.newInstance();
        Set<Integer> monthDayList = FastSet.newInstance();
        Set<WeekDay> weekDayList = FastSet.newInstance();
        Set<Integer> hourList = FastSet.newInstance();
        String freq = null;
        int freqCount = 0;
        for (Recur recur : recurList) {
            monthList.addAll(recur.getMonthList());
            monthDayList.addAll(recur.getMonthDayList());
            weekDayList.addAll(recur.getDayList());
            hourList.addAll(recur.getHourList());
            if (recur.getInterval() != 0 && freq == null) {
                freq = recur.getFrequency();
                freqCount = recur.getInterval();
            }
        }
        if (freq == null && monthList.size() > 0) {
            freq = Recur.MONTHLY;
        } else if (freq == null && (monthDayList.size() > 0 || weekDayList.size() > 0)) {
            freq = Recur.DAILY;
        } else if (freq == null && hourList.size() > 0) {
            freq = Recur.HOURLY;
        }
        if (freq == null) {
            throw new IllegalStateException("Unable to convert intersection");
        }
        Recur newRecur = new Recur(freq, 0);
        if (freqCount != 0) {
            newRecur.setInterval(freqCount);
        }
        newRecur.getMonthList().addAll(monthList);
        newRecur.getMonthDayList().addAll(monthDayList);
        newRecur.getDayList().addAll(weekDayList);
        newRecur.getHourList().addAll(hourList);
        return newRecur;
    }

    protected class VisitorState {
        public boolean isExcluded = false;
        public boolean isIntersection = false;
        public List<Recur> inclRecurList = FastList.newInstance();
        public List<Recur> exRecurList = FastList.newInstance();
        public void addRecur(Recur recur) {
            if (this.isIntersection) {
                if (this.isExcluded) {
                    this.exRecurList.add(recur);
                } else {
                    this.inclRecurList.add(recur);
                }
            } else {
                if (this.isExcluded) {
                    exRuleList.add(new ExRule(recur));
                } else {
                    incRuleList.add(new RRule(recur));
                }
            }
        }
    }
}
