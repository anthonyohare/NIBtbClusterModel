

N = 2500

for i in range(0, N):
    print ("JOB  controller{0} controller.submit".format(i))
    print ("JOB  scenarios{0} scenario.submit".format(i))
print ("JOB  controller{0} controller.submit".format(N))

for i in range(0, N):
    print("PARENT controller{0} CHILD scenarios{1}".format(i,i))
    print("PARENT scenarios{0} CHILD controller{1}".format(i, i+1))


