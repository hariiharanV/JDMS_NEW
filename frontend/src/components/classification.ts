import { CLASSIFICATIONS, type Classification } from '../api/types';

/** True when a document is classified above its type's minimum: only then do lists show the label. */
export function isAboveMinimum(classification: Classification, minimum: Classification | undefined): boolean {
  return minimum !== undefined && CLASSIFICATIONS.indexOf(classification) > CLASSIFICATIONS.indexOf(minimum);
}

/** Which levels the preview watermarks (decision D5: the current rule, confidential and above). */
export function isClassified(classification: Classification): boolean {
  return classification === 'CONFIDENTIAL' || classification === 'RESTRICTED';
}
