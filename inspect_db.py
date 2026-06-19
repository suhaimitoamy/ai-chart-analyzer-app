import sqlite3
import sys

def inspect_database(db_path):
    try:
        # Connect to the SQLite database
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()

        print(f"--- Inspecting Database: {db_path} ---")

        # Get the list of all tables
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
        tables = cursor.fetchall()

        if not tables:
            print("No tables found in the database.")
            return

        print("\nTables found:")
        for table in tables:
            table_name = table[0]
            print(f"- {table_name}")

        print("\n--- Table Contents ---\n")

        # Iterate through each table and fetch its contents
        for table in tables:
            table_name = table[0]
            print(f"Table: {table_name}")
            
            # Get column names
            cursor.execute(f"PRAGMA table_info({table_name});")
            columns = [col[1] for col in cursor.fetchall()]
            print(" | ".join(columns))
            print("-" * 50)
            
            # Get table data
            cursor.execute(f"SELECT * FROM {table_name};")
            rows = cursor.fetchall()
            
            if not rows:
                print("(Empty)")
            else:
                for row in rows:
                    print(" | ".join(map(str, row)))
            
            print("\n" + "=" * 50 + "\n")

    except sqlite3.Error as e:
        print(f"SQLite error: {e}")
    finally:
        if conn:
            conn.close()

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python inspect_db.py <path_to_sqlite_file>")
        print("Example: python inspect_db.py data/xauusd_bot.sqlite")
        sys.exit(1)
        
    database_path = sys.argv[1]
    inspect_database(database_path)
