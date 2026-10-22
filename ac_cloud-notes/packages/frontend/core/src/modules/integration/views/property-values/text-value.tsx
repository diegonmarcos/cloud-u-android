import { PropertyValue } from '@cloud-notes/component';

import * as styles from './styles.css';
export const TextValue = ({ value }: { value: string }) => {
  return (
    <PropertyValue hoverable={false} className={styles.value}>
      {value}
    </PropertyValue>
  );
};
