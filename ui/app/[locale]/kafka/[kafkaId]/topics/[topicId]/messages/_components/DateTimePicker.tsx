"use client";
import type { TimePickerProps } from "@/libs/patternfly/react-core";
import {
  DatePicker,
  InputGroup,
  TimePicker,
} from "@/libs/patternfly/react-core";
import { format, parseISO, setHours, setMinutes } from "date-fns";
import { useEffect, useState } from "react";

export type DateTimePickerProps = {
  isDisabled: boolean;
  value: string | undefined;
  onChange: (value: number) => void;
};

export function DateTimePicker({
  isDisabled,
  value,
  onChange,
}: DateTimePickerProps) {
  const [date, setDate] = useState(value ? parseISO(value) : undefined);

  const time = date ? format(date, "hh:mm aa") : undefined;

  const onSelectTime: TimePickerProps["onChange"] = (
    _,
    time,
    hour,
    minute,
    __,
    isValid,
  ) => {
    if (
      isValid &&
      date &&
      hour != undefined &&
      hour > 0 &&
      (time.includes("AM") || time.includes("PM"))
    ) {
      let newDate = date;
      if (hour !== undefined) {
        newDate = setHours(newDate, hour);
      }
      if (minute !== undefined) {
        newDate = setMinutes(newDate, minute);
      }
      setDate(newDate);
    }
  };

  useEffect(() => {
    if (date) {
      onChange(date.getTime());
    }
  }, [date, onChange]);

  return (
    <InputGroup>
      <DatePicker
        isDisabled={isDisabled}
        value={date ? format(date, "yyyy-MM-dd") : undefined}
        onChange={(_, __, date) => {
          if (date) {
            setDate(date);
            onChange(date.getTime());
          }
        }}
      />
      <TimePicker
        isDisabled={!date || isDisabled}
        time={time ? time : ""}
        onChange={onSelectTime}
      />
    </InputGroup>
  );
}