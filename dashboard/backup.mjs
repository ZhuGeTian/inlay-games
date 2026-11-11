import { DatabaseSync, backup } from 'node:sqlite';
if(!process.env.STUDY_DB || !process.argv[2])throw new Error('Set STUDY_DB and pass a new backup path');
const db=new DatabaseSync(process.env.STUDY_DB);await backup(db,process.argv[2]);db.close();console.log('SQLite backup completed');
