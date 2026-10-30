// Test loader: lets node (type stripping) resolve the vault sources' extensionless imports.
import { register } from 'node:module';
register(
  'data:text/javascript,' +
    encodeURIComponent(
      "export async function resolve(s,c,n){try{return await n(s,c)}catch(e){if(s.startsWith('.')&&!/\\.\\w+$/.test(s))return n(s+'.ts',c);throw e}}"
    )
);
