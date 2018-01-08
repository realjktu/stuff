import mysql.connector
cnx = mysql.connector.connect(user='dump1090', database='dump1090', password='dkjssdnsdkjc', host='home.jktu.org.ua')
cursor = cnx.cursor(prepared=True)

query = ("select data, timestamp from dumpData")
cursor.execute(query)
for (data, timestamp) in cursor:
	print("{} at {:%d %b %Y}".format(data, timestamp))


cursor.close()
cnx.close()

