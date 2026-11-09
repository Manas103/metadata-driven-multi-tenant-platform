export interface FieldDiff {
  fieldName: string;
  priorValue: string;
  restatedValue: string;
  changed: boolean;
}

export interface RestatementDiff {
  factId: string;
  accountId: number;
  symbol: string;
  effectiveDate: string;
  priorAsOfFrom: string;
  restatedAsOfFrom: string;
  fields: FieldDiff[];
}
