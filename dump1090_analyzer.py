import mysql.connector, time
cnx = mysql.connector.connect(user='dump1090', database='dump1090', password='dkjssdnsdkjc', host='172.10.10.2')
cursor = cnx.cursor(prepared=True)

query = ("select param1, param2, param3, param4, param5, param6, param7, param8, param9, param10, param11, param12, param13, param14, param15, param16, param17, param18, param19, param20, param21, param22, timestamp from dumpData where param5='50822E' order by param5, timestamp")
cursor.execute(query)
prev_hexid=''
prev_alt=''
prev_lat=''
prev_lon=''
prev_speed=''
prev_timestamp= 0

cur_hexid=''
cur_alt=''
cur_lat=''
cur_lon=''
cur_speed=''
cur_timestamp=0
start_alt=0
start_speed=0
start_lat=0
start_lon=0
for (param1, param2, param3, param4, param5, param6, param7, param8, param9, param10, param11, param12, param13, param14, param15, param16, param17, param18, param19, param20, param21, param22, timestamp) in cursor:
    print("Data:     hex_id: {}, alt: {}, lat: {}, lon: {}, speed: {} at {:%Y-%m-%d %H:%M:%S}".format(param5, param12, param15, param16, param13, timestamp))
    cur_hexid=param5
    cur_timestamp=time.mktime(timestamp.timetuple())
    time_diff=cur_timestamp-prev_timestamp
    if(param12!=''):
        cur_alt=param12
    else:
        cur_alt=prev_alt
    if(param15!=''):
        cur_lat=param15
    else:
        cur_lat=prev_lat
    if(param16!=''):
        cur_lon=param16
    else:
        cur_lon=prev_lon
    if(cur_speed!=''):
        cur_speed=param13
    else:
        cur_speed=prev_speed
    print("Current : hex_id: {}, alt: {}, lat: {}, lon: {}, speed: {} at {:%Y-%m-%d %H:%M:%S}".format(cur_hexid, cur_alt, cur_lat, cur_lon, cur_speed, timestamp))
    print("Previous: hex_id: {}, alt: {}, lat: {}, lon: {}, speed: {} at {:%Y-%m-%d %H:%M:%S}".format(prev_hexid, prev_alt, prev_lat, prev_lon, prev_speed, timestamp))
    if(cur_hexid!=prev_hexid or time_diff>1800):
        print("Previous start alt: "+str(start_alt))
        print("Previous end alt: "+str(cur_alt))
        print("Previous start speed: "+str(start_speed))
        print("Previous end speed: "+str(cur_speed)) 
        print("Previous start lat: "+str(start_lat))
        print("Previous end lat: "+str(cur_lat)) 
        print("Previous start lon: "+str(start_lon))
        print("Previous end lon: "+str(cur_lon)) 
        print("New flight")
        prev_alt=''
        prev_lat=''
        prev_lon=''
        prev_speed=''
        start_alt=0
        start_speed=0
        start_lat=0
        start_lon=0
    else:
        if(start_alt==0 and cur_alt!=''):
            start_alt=cur_alt
        if(start_speed==0 and cur_speed!=''):
            start_speed=cur_speed
        if(start_lat==0 and cur_lat!=''):
            start_lat=cur_lat
        if(start_lon==0 and cur_lon!=''):
            start_lon=cur_lon
        prev_alt=cur_alt
        prev_lat=cur_lat
        prev_lon=cur_lon
        prev_speed=cur_speed
    prev_hexid=cur_hexid
    prev_timestamp= cur_timestamp
print("Previous start alt: "+str(start_alt))
print("Previous end alt: "+str(cur_alt))
print("Previous start alt: "+str(start_speed))
print("Previous end alt: "+str(cur_speed)) 
print("Previous start lat: "+str(start_lat))
print("Previous end lat: "+str(cur_lat)) 
print("Previous start lon: "+str(start_lon))
print("Previous end lon: "+str(cur_lon)) 


cursor.close()
cnx.close()

